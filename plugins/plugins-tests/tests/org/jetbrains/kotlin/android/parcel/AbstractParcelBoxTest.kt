/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.android.parcel

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.android.synthetic.AndroidComponentRegistrar
import org.jetbrains.kotlin.android.synthetic.test.addAndroidExtensionsRuntimeLibrary
import org.jetbrains.kotlin.backend.common.output.OutputFile
import org.jetbrains.kotlin.backend.common.output.SimpleOutputBinaryFile
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.codegen.CodegenTestCase
import org.jetbrains.kotlin.codegen.getClassFiles
import org.jetbrains.kotlin.utils.PathUtil
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
import org.jetbrains.org.objectweb.asm.ClassWriter.COMPUTE_MAXS
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.tree.ClassNode
import org.jetbrains.org.objectweb.asm.Opcodes.*
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter
import org.junit.internal.RealSystem
import org.junit.runner.JUnitCore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.net.URLClassLoader
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.ByteBuffer
import java.security.ProtectionDomain

abstract class AbstractParcelBoxTest : CodegenTestCase() {
    protected companion object {
        val BASE_DIR = "plugins/android-extensions/android-extensions-compiler/testData/parcel/box"
        val LIBRARY_KT = File(File(BASE_DIR).parentFile, "boxLib.kt")

        private val GENERATED_JUNIT_TEST_FQNAME = "test.JunitTest"
    }

    override fun doTest(filePath: String) {
        super.doTest(File(BASE_DIR, filePath + ".kt").absolutePath)
    }

    private fun getClassloaderWithoutIdeaClasspath(current: ClassLoader): ClassLoader {
        val parent = current.parent ?: return current
        if (current.getResource(PsiElement::class.java.name.replace('.', '/') + ".class") != null) {
            return getClassloaderWithoutIdeaClasspath(parent)
        }
        return current
    }

    private fun getClassLoaderForTest(): ClassLoader {
        val classLoaderWithoutIdeaClasspath = getClassloaderWithoutIdeaClasspath(ClassLoader.getSystemClassLoader())

        val kotlinRuntime = listOf(PathUtil.getKotlinPathsForIdeaPlugin().stdlibPath)
        val layoutLib = listOf(File("ideaSDK/plugins/android/lib/layoutlib.jar"), File("ideaSDK/plugins/android/lib/layoutlib-api.jar"))
        val robolectricDependencies = File("dependencies/robolectric")
                .listFiles { f: File -> f.extension == "jar" }
                .sortedBy { it.nameWithoutExtension }
        val junit = URL(JUnitCore::class.java.classLoader.getResource(JUnitCore::class.java.name.replace('.', '/') + ".class")
                                .file.substringBeforeLast('!'))

        val dependencyUrls = (kotlinRuntime + layoutLib + robolectricDependencies).map { it.toURI().toURL() } + junit

        val additionalFiles = classFileFactory.getClassFiles().sortedBy { it.relativePath } +
                SimpleOutputBinaryFile(emptyList(), GENERATED_JUNIT_TEST_FQNAME.replace('.', '/') + ".class", constructSyntheticTestClass())

        val actualClassLoader = object : URLClassLoader(dependencyUrls.toTypedArray(), classLoaderWithoutIdeaClasspath) {
            private val classesToLoad = additionalFiles.map { it.toClassToLoad() }

            init {
                for (clazz in classesToLoad) {
                    try {
                        defineClass(clazz.name, ByteBuffer.wrap(clazz.bytes), null as? ProtectionDomain?)
                    }
                    catch (e: Throwable) {
                        throw RuntimeException("Can't load class ${clazz.internalName}", e)
                    }
                }
            }

            override fun getResource(name: String): URL? {
                super.getResource(name)?.let { return it }
                val clazz = classesToLoad.firstOrNull { it.internalName + ".class" == name } ?: return null
                return URL("custom", "localhost", 1, name, CustomURLStreamHandler(clazz.bytes))
            }

            override fun getResourceAsStream(name: String): InputStream? {
                super.getResourceAsStream(name)?.let { return it }

                val clazz = classesToLoad.firstOrNull { it.internalName + ".class" == name } ?: return null
                return ByteArrayInputStream(clazz.bytes)
            }
        }

        // Robolectric's SandboxClassLoader throws away the topmost classloader
        return URLClassLoader(emptyArray(), actualClassLoader)
    }

    private class CustomURLStreamHandler(private val bytes: ByteArray) : URLStreamHandler() {
        override fun openConnection(url: URL) = object : URLConnection(url) {
            override fun connect() {}
            override fun getInputStream() = ByteArrayInputStream(bytes)
        }
    }

    private class ClassToLoad(val internalName: String, val bytes: ByteArray) {
        val name: String get() = internalName.replace('/', '.')
    }

    private fun OutputFile.toClassToLoad(): ClassToLoad {
        val bytes = asByteArray()
        val internalName = ClassNode().also { ClassReader(asByteArray()).accept(it, ClassReader.EXPAND_FRAMES) }.name
        return ClassToLoad(internalName, bytes)
    }

    private fun constructSyntheticTestClass(): ByteArray {
        return with(ClassWriter(COMPUTE_MAXS or COMPUTE_FRAMES)) {
            visit(49, ACC_PUBLIC, GENERATED_JUNIT_TEST_FQNAME.replace('.', '/'), null, "java/lang/Object", emptyArray())
            visitSource(null, null)

            with(visitAnnotation("Lorg/junit/runner/RunWith;", true)) {
                visit("value", Type.getType("Lorg/robolectric/RobolectricTestRunner;"))
                visitEnd()
            }

            with(visitAnnotation("Lorg/robolectric/annotation/Config;", true)) {
                visit("manifest", "--none")
                visitEnd()
            }

            with(visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)) {
                visitVarInsn(ALOAD, 0)
                visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)

                visitInsn(RETURN)
                visitMaxs(-1, -1)
                visitEnd()
            }

            with(visitMethod(ACC_PUBLIC, "test", "()V", null, null)) {
                visitAnnotation("Lorg/junit/Test;", true).visitEnd()

                val v = InstructionAdapter(this)

                val assertionOk = Label()

                v.invokestatic("test/TestKt", "box", "()Ljava/lang/String;", false) // -> ret
                v.dup() // -> ret, ret
                v.aconst("OK") // -> ret, ret, ok
                v.invokevirtual("java/lang/String", "equals", "(Ljava/lang/Object;)Z", false) // -> ret, eq
                v.ifne(assertionOk) // -> ret

                val assertionErrorType = Type.getObjectType("java/lang/AssertionError")

                v.anew(assertionErrorType) // -> ret, ae
                v.dupX1() // -> ae, ret, ae
                v.swap() // -> ae, ae, ret
                v.invokespecial(assertionErrorType.internalName, "<init>", "(Ljava/lang/Object;)V", false) // -> ae
                v.athrow()

                v.visitLabel(assertionOk)
                v.pop() // -> [empty]
                v.areturn(Type.VOID_TYPE)

                visitMaxs(-1, -1)
                visitEnd()
            }

            visitEnd()
            toByteArray()
        }
    }

    override fun doMultiFileTest(wholeFile: File, files: List<TestFile>, javaFilesDir: File?) {
        compile(files + TestFile(LIBRARY_KT.name, LIBRARY_KT.readText()), javaFilesDir)

        val oldContextClassLoader = Thread.currentThread().contextClassLoader
        try {
            val classLoaderForTest = getClassLoaderForTest()
            Thread.currentThread().contextClassLoader = classLoaderForTest

            val junitCore = Class.forName(JUnitCore::class.java.name, true, classLoaderForTest)
            val realSystem = Class.forName(RealSystem::class.java.name, true, classLoaderForTest)

            val mainMethod = junitCore.declaredMethods.single { it.name == "runMain" }
            val result = mainMethod.invoke(junitCore.newInstance(), realSystem.newInstance(), arrayOf(GENERATED_JUNIT_TEST_FQNAME))
            val wasSuccessful = result::class.java.getMethod("wasSuccessful").invoke(result) as Boolean
            if (!wasSuccessful) throw AssertionError("Test is failed. See stdout for more information")
        } catch (e: Throwable) {
            throw AssertionError(classFileFactory.createText(), e)
        } finally {
            Thread.currentThread().contextClassLoader = oldContextClassLoader
        }
    }

    override fun setupEnvironment(environment: KotlinCoreEnvironment) {
        AndroidComponentRegistrar.registerParcelExtensions(environment.project)
        addAndroidExtensionsRuntimeLibrary(environment)
        environment.updateClasspath(listOf(JvmClasspathRoot(File("ideaSDK/plugins/android/lib/layoutlib.jar"))))
    }
}