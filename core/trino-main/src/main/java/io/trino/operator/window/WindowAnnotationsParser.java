/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.operator.window;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.trino.metadata.Signature;
import io.trino.metadata.TypeVariableConstraint;
import io.trino.spi.function.Description;
import io.trino.spi.function.WindowFunction;
import io.trino.spi.function.WindowFunctionSignature;
import io.trino.spi.type.TypeSignature;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.metadata.Signature.typeVariable;
import static io.trino.sql.analyzer.TypeSignatureTranslator.parseTypeSignature;

public final class WindowAnnotationsParser
{
    private WindowAnnotationsParser() {}

    public static List<SqlWindowFunction> parseFunctionDefinition(Class<? extends WindowFunction> clazz)
    {
        WindowFunctionSignature[] signatures = clazz.getAnnotationsByType(WindowFunctionSignature.class);
        checkArgument(signatures.length > 0, "Class is not annotated with @WindowFunctionSignature: %s", clazz.getName());
        return Stream.of(signatures)
                .map(signature -> parse(clazz, signature))
                .collect(toImmutableList());
    }

    private static SqlWindowFunction parse(Class<? extends WindowFunction> clazz, WindowFunctionSignature window)
    {
        List<TypeVariableConstraint> typeVariables = ImmutableList.of();
        if (!window.typeVariable().isEmpty()) {
            typeVariables = ImmutableList.of(typeVariable(window.typeVariable()));
        }

        List<TypeSignature> argumentTypes = Stream.of(window.argumentTypes())
                .map(type -> parseTypeSignature(type, ImmutableSet.of()))
                .collect(toImmutableList());

        Signature signature = new Signature(
                window.name(),
                typeVariables,
                ImmutableList.of(),
                parseTypeSignature(window.returnType(), ImmutableSet.of()),
                argumentTypes,
                false);

        Optional<String> description = Optional.ofNullable(clazz.getAnnotation(Description.class)).map(Description::value);

        boolean deprecated = clazz.getAnnotationsByType(Deprecated.class).length > 0;

        return new SqlWindowFunction(signature, description, deprecated, new ReflectionWindowFunctionSupplier(window.argumentTypes().length, clazz));
    }
}
