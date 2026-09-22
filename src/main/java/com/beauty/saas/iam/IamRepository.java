package com.beauty.saas.iam;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class IamRepository {
    private final JdbcTemplate jdbc;
    public List<Map<String,Object>> rows(String sql, Object... args) {
        return jdbc.query(sql, (rs, index) -> {
            Map<String,Object> row = new LinkedHashMap<>();
            for (int i=1; i<=rs.getMetaData().getColumnCount(); i++) {
                String[] parts = rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT).split("_");
                StringBuilder name = new StringBuilder(parts[0]);
                for (int j=1; j<parts.length; j++) name.append(Character.toUpperCase(parts[j].charAt(0))).append(parts[j].substring(1));
                Object value = rs.getObject(i);
                row.put(name.toString(), value instanceof Timestamp time ? time.toLocalDateTime() : value);
            }
            return row;
        }, args);
    }
    public Map<String,Object> one(String sql, Object... args) { return rows(sql,args).stream().findFirst().orElse(null); }
    public long count(String sql, Object... args) { Long n = jdbc.queryForObject(sql, Long.class, args); return n == null ? 0 : n; }
    public int update(String sql, Object... args) { return jdbc.update(sql,args); }
    public long insert(String sql, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i=0; i<args.length; i++) statement.setObject(i+1,args[i]);
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
    public static long id(Map<String,Object> row, String name) { return ((Number) row.get(name)).longValue(); }
    public static String text(Map<String,Object> row, String name) { return Objects.toString(row.get(name), ""); }
}
