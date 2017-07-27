/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.compilerRunner;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.cli.common.arguments.Argument;
import org.jetbrains.kotlin.cli.common.arguments.CommonToolArguments;
import org.jetbrains.kotlin.cli.common.arguments.ParseCommandLineArgumentsKt;
import org.jetbrains.kotlin.utils.StringsKt;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class ArgumentUtils {
    private ArgumentUtils() {}

    @NotNull
    public static List<String> convertArgumentsToStringList(@NotNull CommonToolArguments arguments)
            throws InstantiationException, IllegalAccessException, InvocationTargetException {
        List<String> result = new ArrayList<>();
        convertArgumentsToStringList(arguments, arguments.getClass().newInstance(), arguments.getClass(), result);
        result.addAll(arguments.getFreeArgs());
        return result;
    }

    private static void convertArgumentsToStringList(
            @NotNull CommonToolArguments arguments,
            @NotNull CommonToolArguments defaultArguments,
            @NotNull Class<?> clazz,
            @NotNull List<String> result
    ) throws IllegalAccessException, InstantiationException, InvocationTargetException {
        for (Method method : clazz.getDeclaredMethods()) {
            Argument argument = method.getAnnotation(Argument.class);
            if (argument == null) continue;

            Object value;
            Object defaultValue;
            try {
                value = method.invoke(arguments);
                defaultValue = method.invoke(defaultArguments);
            }
            catch (IllegalAccessException ignored) {
                // skip this method
                continue;
            }

            if (value == null || Objects.equals(value, defaultValue)) continue;

            Class<?> methodType = method.getReturnType();

            if (methodType.isArray()) {
                Object[] values = (Object[]) value;
                if (values.length == 0) continue;
                value = StringsKt.join(Arrays.asList(values), ",");
            }

            result.add(argument.value());

            if (methodType == boolean.class || methodType == Boolean.class) continue;

            if (ParseCommandLineArgumentsKt.isAdvanced(argument)) {
                result.set(result.size() - 1, argument.value() + "=" + value.toString());
            }
            else {
                result.add(value.toString());
            }
        }

        Class<?> superClazz = clazz.getSuperclass();
        if (superClazz != null) {
            convertArgumentsToStringList(arguments, defaultArguments, superClazz, result);
        }
    }
}
