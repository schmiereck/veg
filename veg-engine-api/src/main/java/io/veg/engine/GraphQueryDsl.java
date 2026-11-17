package io.veg.engine;

import io.veg.model.ComparisonOperator;
import io.veg.model.QueryRow;
import io.veg.model.SortDirection;
import io.veg.model.Value;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compact declarative graph query DSL compiler targeting the storage-independent {@link GraphQuery} builder.
 *
 * <p>Supported syntax:
 * <pre>
 * [USING DERIVED]
 * MATCH (a:TypeName)
 * [JOIN (a)-[predicate]->(b[:TargetType])]...
 * [WHERE a.attr = "val" AND (a)-[rel]->(b) ...]
 * [SELECT a.attr, b.other ...]
 * [ORDER BY a.attr ASC|DESC, ...]
 * [LIMIT n] [OFFSET m]
 * </pre>
 */
public final class GraphQueryDsl {

    private static final Pattern CLAUSE_KEYWORD = Pattern.compile(
            "(?i)(?:^|\\s)(USING\\s+DERIVED|MATCH|JOIN|WHERE|SELECT|ORDER\\s+BY|LIMIT|OFFSET)(?=\\s|$)"
    );
    private static final Pattern MATCH_PATTERN = Pattern.compile(
            "^\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*([A-Za-z_][A-Za-z0-9_.:-]*)\\s*\\)$"
    );
    private static final Pattern JOIN_PATTERN = Pattern.compile(
            "^\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)-\\[\\s*([A-Za-z_][A-Za-z0-9_.:-]*)\\s*\\]->\\(\\s*([A-Za-z_][A-Za-z0-9_]*)(?:\\s*:\\s*([A-Za-z_][A-Za-z0-9_.:-]*))?\\s*\\)$"
    );
    private static final Pattern EDGE_FILTER_PATTERN = Pattern.compile(
            "^\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)-\\[\\s*([A-Za-z_][A-Za-z0-9_.:-]*)\\s*\\]->\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)$"
    );
    private static final Pattern ATTR_FILTER_PATTERN = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_.:-]*)\\s*(<=|>=|!=|=|<|>|\\b(?i:CONTAINS)\\b|\\b(?i:STARTS_WITH)\\b)\\s*(.+)$"
    );

    private record Clause(String keyword, String body) {
    }

    private GraphQueryDsl() {
    }

    public static List<QueryRow> execute(GraphReader reader, String queryText) {
        return parse(reader, queryText).toList();
    }

    public static GraphQuery parse(GraphReader reader, String queryText) {
        Objects.requireNonNull(reader, "reader must not be null");
        if (queryText == null || queryText.isBlank()) {
            throw new QuerySyntaxException("Query text must not be null or blank");
        }

        List<Clause> clauses = splitClauses(queryText.trim());
        if (clauses.isEmpty()) {
            throw new QuerySyntaxException("Could not recognize query clauses in: " + queryText);
        }

        GraphQuery query = GraphQuery.against(reader);
        int idx = 0;

        if ("USING DERIVED".equals(clauses.get(idx).keyword())) {
            if (!clauses.get(idx).body().isBlank()) {
                throw new QuerySyntaxException("Unexpected tokens after USING DERIVED: " + clauses.get(idx).body());
            }
            query.includeDerived(true);
            idx++;
        }

        if (idx >= clauses.size() || !"MATCH".equals(clauses.get(idx).keyword())) {
            throw new QuerySyntaxException("Expected MATCH clause");
        }
        Matcher matchMatcher = MATCH_PATTERN.matcher(clauses.get(idx).body().trim());
        if (!matchMatcher.matches()) {
            throw new QuerySyntaxException("Invalid MATCH clause: " + clauses.get(idx).body());
        }
        query.match(matchMatcher.group(1), matchMatcher.group(2));
        idx++;

        while (idx < clauses.size() && "JOIN".equals(clauses.get(idx).keyword())) {
            Matcher joinMatcher = JOIN_PATTERN.matcher(clauses.get(idx).body().trim());
            if (!joinMatcher.matches()) {
                throw new QuerySyntaxException("Invalid JOIN clause: " + clauses.get(idx).body());
            }
            query.traverse(joinMatcher.group(1), joinMatcher.group(2), joinMatcher.group(3), joinMatcher.group(4));
            idx++;
        }

        if (idx < clauses.size() && "WHERE".equals(clauses.get(idx).keyword())) {
            parseWhere(query, clauses.get(idx).body());
            idx++;
        }

        if (idx < clauses.size() && "SELECT".equals(clauses.get(idx).keyword())) {
            String body = clauses.get(idx).body().trim();
            if (body.isBlank()) {
                throw new QuerySyntaxException("SELECT clause must specify at least one projection");
            }
            String[] cols = body.split(",");
            for (int i = 0; i < cols.length; i++) {
                cols[i] = cols[i].trim();
                if (cols[i].isBlank()) {
                    throw new QuerySyntaxException("Empty projection entry in SELECT clause");
                }
            }
            query.select(cols);
            idx++;
        }

        if (idx < clauses.size() && "ORDER BY".equals(clauses.get(idx).keyword())) {
            parseOrderBy(query, clauses.get(idx).body());
            idx++;
        }

        while (idx < clauses.size()) {
            String kw = clauses.get(idx).keyword();
            String body = clauses.get(idx).body().trim();
            if ("LIMIT".equals(kw)) {
                query.limit(parseNonNegativeInt(body, "LIMIT"));
            } else if ("OFFSET".equals(kw)) {
                query.offset(parseNonNegativeInt(body, "OFFSET"));
            } else {
                throw new QuerySyntaxException("Unexpected or out-of-order clause: " + kw);
            }
            idx++;
        }

        return query;
    }

    private static List<Clause> splitClauses(String query) {
        List<int[]> spans = new ArrayList<>();
        List<String> keywords = new ArrayList<>();

        int n = query.length();
        boolean inQuotes = false;
        for (int i = 0; i < n; i++) {
            char c = query.charAt(i);
            if (c == '\\' && inQuotes && i + 1 < n) {
                i++;
                continue;
            }
            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (inQuotes) {
                continue;
            }
            if (i == 0 || Character.isWhitespace(query.charAt(i - 1))) {
                Matcher m = CLAUSE_KEYWORD.matcher(query.substring(i));
                if (m.lookingAt()) {
                    String kw = m.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
                    spans.add(new int[]{i, i + m.end()});
                    keywords.add(kw);
                    i += m.end() - 1;
                }
            }
        }

        if (spans.isEmpty() || spans.getFirst()[0] != 0) {
            throw new QuerySyntaxException("Query must begin with USING DERIVED or MATCH");
        }

        List<Clause> clauses = new ArrayList<>(spans.size());
        for (int i = 0; i < spans.size(); i++) {
            int bodyStart = spans.get(i)[1];
            int bodyEnd = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : n;
            clauses.add(new Clause(keywords.get(i), query.substring(bodyStart, bodyEnd).trim()));
        }
        return clauses;
    }

    private static void parseWhere(GraphQuery query, String body) {
        if (body.isBlank()) {
            throw new QuerySyntaxException("WHERE clause cannot be empty");
        }
        List<String> conditions = splitTopLevelAnd(body);
        for (String cond : conditions) {
            String trimmed = cond.trim();
            if (trimmed.isEmpty()) {
                throw new QuerySyntaxException("Empty condition in WHERE clause");
            }
            Matcher edgeMatcher = EDGE_FILTER_PATTERN.matcher(trimmed);
            if (edgeMatcher.matches()) {
                query.whereConnected(edgeMatcher.group(1), edgeMatcher.group(2), edgeMatcher.group(3));
                continue;
            }
            Matcher attrMatcher = ATTR_FILTER_PATTERN.matcher(trimmed);
            if (attrMatcher.matches()) {
                String alias = attrMatcher.group(1);
                String predicate = attrMatcher.group(2);
                ComparisonOperator op = parseOperator(attrMatcher.group(3));
                Value literal = parseLiteral(attrMatcher.group(4).trim());
                query.whereAttribute(alias, predicate, op, literal);
                continue;
            }
            throw new QuerySyntaxException("Invalid WHERE condition: " + trimmed);
        }
    }

    private static List<String> splitTopLevelAnd(String text) {
        List<String> parts = new ArrayList<>();
        boolean inQuotes = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && inQuotes && i + 1 < text.length()) {
                i++;
                continue;
            }
            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (!inQuotes && i > 0 && Character.isWhitespace(text.charAt(i - 1))
                    && i + 4 <= text.length()
                    && text.substring(i, i + 3).equalsIgnoreCase("AND")
                    && Character.isWhitespace(text.charAt(i + 3))) {
                parts.add(text.substring(start, i));
                start = i + 4;
                i += 3;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static ComparisonOperator parseOperator(String raw) {
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "=" -> ComparisonOperator.EQ;
            case "!=" -> ComparisonOperator.NEQ;
            case "<" -> ComparisonOperator.LT;
            case "<=" -> ComparisonOperator.LTE;
            case ">" -> ComparisonOperator.GT;
            case ">=" -> ComparisonOperator.GTE;
            case "CONTAINS" -> ComparisonOperator.CONTAINS;
            case "STARTS_WITH" -> ComparisonOperator.STARTS_WITH;
            default -> throw new QuerySyntaxException("Unsupported operator: " + raw);
        };
    }

    private static Value parseLiteral(String literal) {
        if (literal.startsWith("\"") && literal.endsWith("\"") && literal.length() >= 2) {
            String inner = literal.substring(1, literal.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\\", "\\");
            return Value.of(inner);
        }
        if ("true".equalsIgnoreCase(literal) || "false".equalsIgnoreCase(literal)) {
            return Value.of(Boolean.parseBoolean(literal));
        }
        if (literal.matches("-?\\d+\\.\\d+")) {
            return Value.of(new BigDecimal(literal));
        }
        if (literal.matches("-?\\d+")) {
            try {
                return Value.of(Long.parseLong(literal));
            } catch (NumberFormatException ex) {
                throw new QuerySyntaxException("Integer literal out of range: " + literal);
            }
        }
        throw new QuerySyntaxException("Invalid literal value: " + literal);
    }

    private static void parseOrderBy(GraphQuery query, String body) {
        if (body.isBlank()) {
            throw new QuerySyntaxException("ORDER BY clause cannot be empty");
        }
        for (String item : body.split(",")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                throw new QuerySyntaxException("Empty ORDER BY item");
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length > 2) {
                throw new QuerySyntaxException("Invalid ORDER BY specification: " + trimmed);
            }
            SortDirection dir = SortDirection.ASC;
            if (parts.length == 2) {
                if ("ASC".equalsIgnoreCase(parts[1])) {
                    dir = SortDirection.ASC;
                } else if ("DESC".equalsIgnoreCase(parts[1])) {
                    dir = SortDirection.DESC;
                } else {
                    throw new QuerySyntaxException("Invalid sort direction in ORDER BY: " + parts[1]);
                }
            }
            query.orderBy(parts[0], dir);
        }
    }

    private static int parseNonNegativeInt(String body, String clauseName) {
        try {
            int val = Integer.parseInt(body);
            if (val < 0) {
                throw new QuerySyntaxException(clauseName + " must not be negative: " + val);
            }
            return val;
        } catch (NumberFormatException ex) {
            throw new QuerySyntaxException("Invalid integer in " + clauseName + ": " + body);
        }
    }
}
