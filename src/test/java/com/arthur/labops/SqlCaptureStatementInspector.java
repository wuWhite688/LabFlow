package com.arthur.labops;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hibernate.resource.jdbc.spi.StatementInspector;

public class SqlCaptureStatementInspector implements StatementInspector {

    private static final CopyOnWriteArrayList<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT));
        return sql;
    }

    static void clear() {
        STATEMENTS.clear();
    }

    static List<String> snapshot() {
        return List.copyOf(STATEMENTS);
    }
}
