package com.edupulse.retrievalpre;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

record MetadataFilter(Mode mode, List<Condition> conditions) {

    static final int MAX_CONDITIONS = 16;
    static final int MAX_VALUE_LENGTH = 512;
    static final String VALID_FIELDS = Arrays.stream(Field.values())
            .map(field -> field.key).collect(Collectors.joining(", "));

    private static final Map<String, Field> FIELDS = Arrays.stream(Field.values())
            .collect(Collectors.toUnmodifiableMap(field -> field.key, field -> field));

    MetadataFilter {
        conditions = List.copyOf(conditions);
    }

    static MetadataFilter parse(List<String> filters, String mode) {
        Mode parsedMode = switch (mode == null ? "AND" : mode) {
            case "AND" -> Mode.AND;
            case "OR" -> Mode.OR;
            default -> throw new InvalidFilter("filterMode must be AND or OR.");
        };
        if (filters == null || filters.isEmpty()) {
            return new MetadataFilter(parsedMode, List.of());
        }
        if (filters.size() > MAX_CONDITIONS) {
            throw new InvalidFilter("At most " + MAX_CONDITIONS + " filters are allowed.");
        }
        return new MetadataFilter(parsedMode, filters.stream().map(MetadataFilter::parseCondition).toList());
    }

    private static Condition parseCondition(String filter) {
        String[] parts = filter.split(":", 3);
        if (parts.length != 3 || parts[2].isBlank() || parts[2].length() > MAX_VALUE_LENGTH
                || parts[2].indexOf('\0') >= 0) {
            throw new InvalidFilter("Use field:operator:value with a non-blank value of at most "
                    + MAX_VALUE_LENGTH + " characters.");
        }

        Field field = FIELDS.get(parts[0]);
        if (field == null) {
            throw new InvalidFilter("Unknown metadata field.");
        }
        Operator operator = switch (parts[1]) {
            case "eq" -> Operator.EQ;
            case "ne" -> Operator.NE;
            case "gt" -> Operator.GT;
            case "gte" -> Operator.GTE;
            case "lt" -> Operator.LT;
            case "lte" -> Operator.LTE;
            default -> throw new InvalidFilter("Unknown filter operator. Use eq, ne, gt, gte, lt or lte.");
        };
        if (field.type != Type.INTEGER && operator != Operator.EQ && operator != Operator.NE) {
            throw new InvalidFilter("Text and boolean fields accept only eq and ne.");
        }
        return new Condition(field, operator, field.parseValue(parts[2]));
    }

    enum Mode { AND, OR }

    enum Operator {
        EQ("="), NE("<>"), GT(">"), GTE(">="), LT("<"), LTE("<=");

        final String sql;

        Operator(String sql) {
            this.sql = sql;
        }
    }

    enum Type { TEXT, INTEGER, BOOLEAN }

    enum Field {
        DOC_ID("doc_id", Type.TEXT),
        SOURCE("source", Type.TEXT),
        CHECKSUM("checksum", Type.TEXT),
        SOURCE_URL("source_url", Type.TEXT),
        VERSION("version", Type.INTEGER),
        PAGE("page", Type.INTEGER),
        CHUNK_INDEX("chunk_index", Type.INTEGER),
        IS_LATEST("is_latest", Type.BOOLEAN);

        final String key;
        final Type type;

        Field(String key, Type type) {
            this.key = key;
            this.type = type;
        }

        Object parseValue(String value) {
            return switch (type) {
                case TEXT -> value;
                case INTEGER -> parseInteger(value);
                case BOOLEAN -> switch (value) {
                    case "true" -> true;
                    case "false" -> false;
                    default -> throw new InvalidFilter(key + " must be true or false.");
                };
            };
        }

        private int parseInteger(String value) {
            int minimum = this == CHUNK_INDEX ? 0 : 1;
            try {
                int number = Integer.parseInt(value);
                if (number >= minimum) {
                    return number;
                }
            } catch (NumberFormatException ex) {
                throw new InvalidFilter(key + " must be an integer of at least " + minimum + ".");
            }
            throw new InvalidFilter(key + " must be an integer of at least " + minimum + ".");
        }
    }

    record Condition(Field field, Operator operator, Object value) {}

    static final class InvalidFilter extends IllegalArgumentException {
        InvalidFilter(String message) {
            super(message);
        }
    }
}
