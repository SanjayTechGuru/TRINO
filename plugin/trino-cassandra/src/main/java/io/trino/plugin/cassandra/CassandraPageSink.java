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
package io.trino.plugin.cassandra;

import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.LocalDate;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ProtocolVersion;
import com.datastax.driver.core.querybuilder.Insert;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Shorts;
import com.google.common.primitives.SignedBytes;
import io.airlift.slice.Slice;
import io.trino.spi.Page;
import io.trino.spi.TrinoException;
import io.trino.spi.block.Block;
import io.trino.spi.connector.ConnectorPageSink;
import io.trino.spi.type.Type;
import io.trino.spi.type.UuidType;
import io.trino.spi.type.VarcharType;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.datastax.driver.core.querybuilder.QueryBuilder.bindMarker;
import static com.datastax.driver.core.querybuilder.QueryBuilder.insertInto;
import static com.google.common.base.Preconditions.checkArgument;
import static io.trino.plugin.cassandra.util.CassandraCqlUtils.ID_COLUMN_NAME;
import static io.trino.plugin.cassandra.util.CassandraCqlUtils.validColumnName;
import static io.trino.plugin.cassandra.util.CassandraCqlUtils.validSchemaName;
import static io.trino.plugin.cassandra.util.CassandraCqlUtils.validTableName;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateTimeEncoding.unpackMillisUtc;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MILLIS;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.UuidType.trinoUuidToJavaUuid;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static java.lang.Float.intBitsToFloat;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

public class CassandraPageSink
        implements ConnectorPageSink
{
    private static final DateTimeFormatter DATE_FORMATTER = ISODateTimeFormat.date().withZoneUTC();

    private final CassandraSession cassandraSession;
    private final PreparedStatement insert;
    private final List<Type> columnTypes;
    private final boolean generateUuid;
    private final int batchSize;
    private final Function<Long, Object> toCassandraDate;
    private final BatchStatement batchStatement = new BatchStatement();

    public CassandraPageSink(
            CassandraSession cassandraSession,
            ProtocolVersion protocolVersion,
            String schemaName,
            String tableName,
            List<String> columnNames,
            List<Type> columnTypes,
            boolean generateUuid,
            int batchSize)
    {
        this.cassandraSession = requireNonNull(cassandraSession, "cassandraSession");
        requireNonNull(schemaName, "schemaName is null");
        requireNonNull(tableName, "tableName is null");
        requireNonNull(columnNames, "columnNames is null");
        this.columnTypes = ImmutableList.copyOf(requireNonNull(columnTypes, "columnTypes is null"));
        this.generateUuid = generateUuid;
        this.batchSize = batchSize;

        if (protocolVersion.toInt() <= ProtocolVersion.V3.toInt()) {
            this.toCassandraDate = value -> DATE_FORMATTER.print(TimeUnit.DAYS.toMillis(value));
        }
        else {
            this.toCassandraDate = value -> LocalDate.fromDaysSinceEpoch(toIntExact(value));
        }

        Insert insert = insertInto(validSchemaName(schemaName), validTableName(tableName));
        if (generateUuid) {
            insert.value(ID_COLUMN_NAME, bindMarker());
        }
        for (int i = 0; i < columnNames.size(); i++) {
            String columnName = columnNames.get(i);
            checkArgument(columnName != null, "columnName is null at position: %s", i);
            insert.value(validColumnName(columnName), bindMarker());
        }
        this.insert = cassandraSession.prepare(insert);
    }

    @Override
    public CompletableFuture<?> appendPage(Page page)
    {
        for (int position = 0; position < page.getPositionCount(); position++) {
            List<Object> values = new ArrayList<>(columnTypes.size() + 1);
            if (generateUuid) {
                values.add(UUID.randomUUID());
            }

            for (int channel = 0; channel < page.getChannelCount(); channel++) {
                appendColumn(values, page, position, channel);
            }

            batchStatement.add(insert.bind(values.toArray()));

            if (batchStatement.size() >= batchSize) {
                cassandraSession.execute(batchStatement);
                batchStatement.clear();
            }
        }
        return NOT_BLOCKED;
    }

    private void appendColumn(List<Object> values, Page page, int position, int channel)
    {
        Block block = page.getBlock(channel);
        Type type = columnTypes.get(channel);
        if (block.isNull(position)) {
            values.add(null);
        }
        else if (BOOLEAN.equals(type)) {
            values.add(type.getBoolean(block, position));
        }
        else if (BIGINT.equals(type)) {
            values.add(type.getLong(block, position));
        }
        else if (INTEGER.equals(type)) {
            values.add(toIntExact(type.getLong(block, position)));
        }
        else if (SMALLINT.equals(type)) {
            values.add(Shorts.checkedCast(type.getLong(block, position)));
        }
        else if (TINYINT.equals(type)) {
            values.add(SignedBytes.checkedCast(type.getLong(block, position)));
        }
        else if (DOUBLE.equals(type)) {
            values.add(type.getDouble(block, position));
        }
        else if (REAL.equals(type)) {
            values.add(intBitsToFloat(toIntExact(type.getLong(block, position))));
        }
        else if (DATE.equals(type)) {
            values.add(toCassandraDate.apply(type.getLong(block, position)));
        }
        else if (TIMESTAMP_TZ_MILLIS.equals(type)) {
            values.add(new Timestamp(unpackMillisUtc(type.getLong(block, position))));
        }
        else if (type instanceof VarcharType) {
            values.add(type.getSlice(block, position).toStringUtf8());
        }
        else if (VARBINARY.equals(type)) {
            values.add(type.getSlice(block, position).toByteBuffer());
        }
        else if (UuidType.UUID.equals(type)) {
            values.add(trinoUuidToJavaUuid(type.getSlice(block, position)));
        }
        else {
            throw new TrinoException(NOT_SUPPORTED, "Unsupported column type: " + type.getDisplayName());
        }
    }

    @Override
    public CompletableFuture<Collection<Slice>> finish()
    {
        if (batchStatement.size() > 0) {
            cassandraSession.execute(batchStatement);
            batchStatement.clear();
        }

        // the committer does not need any additional info
        return completedFuture(ImmutableList.of());
    }

    @Override
    public void abort() {}
}
