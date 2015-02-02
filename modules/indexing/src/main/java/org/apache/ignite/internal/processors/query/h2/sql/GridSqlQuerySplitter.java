/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.apache.ignite.internal.processors.query.h2.sql;

import org.apache.ignite.*;
import org.apache.ignite.internal.processors.cache.query.*;

import java.sql.*;
import java.util.*;

import static org.apache.ignite.internal.processors.query.h2.sql.GridSqlFunctionType.*;

/**
 * Splits a single SQL query into two step map-reduce query.
 */
public class GridSqlQuerySplitter {
    /** */
    private static final String TABLE_PREFIX = "__T";

    /** */
    private static final String COLUMN_PREFIX = "__C";

    /**
     * @param idx Index of table.
     * @return Table name.
     */
    private static String table(int idx) {
        return TABLE_PREFIX + idx;
    }

    /**
     * @param idx Index of column.
     * @return Generated by index column alias.
     */
    private static String columnName(int idx) {
        return COLUMN_PREFIX + idx;
    }

    /**
     * @param conn Connection.
     * @param query Query.
     * @param params Parameters.
     * @return Two step query.
     */
    public static GridCacheTwoStepQuery split(Connection conn, String query, Object[] params) {
        GridSqlSelect srcQry = GridSqlQueryParser.parse(conn, query);

        final String mergeTable = table(0);

        GridSqlSelect mapQry = srcQry.clone();
        GridSqlSelect rdcQry = new GridSqlSelect().from(table(mergeTable));

        // Split all select expressions into map-reduce parts.
        List<GridSqlElement> mapExps = new ArrayList<>(srcQry.allExpressions());
        GridSqlElement[] rdcExps = new GridSqlElement[srcQry.select().size()];

        for (int i = 0, len = mapExps.size(); i < len; i++) // Remember len because mapExps list can grow.
            splitSelectExpression(mapExps, rdcExps, i);

        // Fill select expressions.
        mapQry.clearSelect();

        for (GridSqlElement exp : mapExps)
            mapQry.addSelectExpression(exp);

        for (GridSqlElement rdcExp : rdcExps)
            rdcQry.addSelectExpression(rdcExp);

        // -- GROUP BY
        if (!srcQry.groups().isEmpty()) {
            mapQry.clearGroups();

            for (int col : srcQry.groupColumns())
                mapQry.addGroupExpression(column(((GridSqlAlias)mapExps.get(col)).alias()));

            for (int col : srcQry.groupColumns())
                rdcQry.addGroupExpression(column(((GridSqlAlias)mapExps.get(col)).alias()));
        }

        // -- HAVING
        if (srcQry.having() != null) {
            // TODO Find aggregate functions in HAVING clause.
            rdcQry.whereAnd(column(columnName(srcQry.havingColumn())));

            mapQry.having(null);
        }

        // -- ORDER BY
        if (!srcQry.sort().isEmpty()) {
            for (GridSqlSortColumn sortCol : srcQry.sort().values())
                rdcQry.addSort(column(((GridSqlAlias)mapExps.get(sortCol.column())).alias()), sortCol);
        }

        // -- LIMIT
        if (srcQry.limit() != null)
            rdcQry.limit(srcQry.limit());

        // -- OFFSET
        if (srcQry.offset() != null) {
            mapQry.offset(null);

            rdcQry.offset(srcQry.offset());
        }

        // -- DISTINCT
        if (srcQry.distinct()) {
            mapQry.distinct(false);
            rdcQry.distinct(true);
        }

        // Build resulting two step query.
        GridCacheTwoStepQuery res = new GridCacheTwoStepQuery(rdcQry.getSQL());

        res.addMapQuery(mergeTable, mapQry.getSQL(), params);

        return res;
    }

    /**
     * @param mapSelect Selects for map query.
     * @param rdcSelect Selects for reduce query.
     * @param idx Index.
     */
    private static void splitSelectExpression(List<GridSqlElement> mapSelect, GridSqlElement[] rdcSelect, int idx) {
        GridSqlElement el = mapSelect.get(idx);

        GridSqlAlias alias = null;

        if (el instanceof GridSqlAlias) { // Unwrap from alias.
            alias = (GridSqlAlias)el;
            el = alias.child();
        }

        if (el instanceof GridSqlAggregateFunction) {
            GridSqlAggregateFunction agg = (GridSqlAggregateFunction)el;

            GridSqlElement mapAgg, rdcAgg;

            String mapAggAlias = columnName(idx);

            switch (agg.type()) {
                case AVG: // SUM( AVG(CAST(x AS DOUBLE))*COUNT(x) )/SUM( COUNT(x) ).
                    //-- COUNT(x) map
                    GridSqlElement cntMapAgg = aggregate(agg.distinct(), COUNT).addChild(agg.child());

                    // Add generated alias to COUNT(x).
                    // Using size as index since COUNT will be added as the last select element to the map query.
                    String cntMapAggAlias = columnName(mapSelect.size());

                    cntMapAgg = alias(cntMapAggAlias, cntMapAgg);

                    mapSelect.add(cntMapAgg);

                    //-- AVG(CAST(x AS DOUBLE)) map
                    mapAgg = aggregate(agg.distinct(), AVG).addChild( // Add function argument.
                        function(CAST).setCastType("DOUBLE").addChild(agg.child()));

                    //-- SUM( AVG(x)*COUNT(x) )/SUM( COUNT(x) ) reduce
                    GridSqlElement sumUpRdc = aggregate(false, SUM).addChild(
                        op(GridSqlOperationType.MULTIPLY,
                                column(mapAggAlias),
                                column(cntMapAggAlias)));

                    GridSqlElement sumDownRdc = aggregate(false, SUM).addChild(column(cntMapAggAlias));

                    rdcAgg = op(GridSqlOperationType.DIVIDE, sumUpRdc, sumDownRdc);

                    break;

                case SUM: // SUM( SUM(x) )
                case MAX: // MAX( MAX(x) )
                case MIN: // MIN( MIN(x) )
                    mapAgg = aggregate(agg.distinct(), agg.type()).addChild(agg.child());

                    rdcAgg = aggregate(agg.distinct(), agg.type()).addChild(column(mapAggAlias));

                    break;

                case COUNT_ALL: // CAST(SUM( COUNT(*) ) AS BIGINT)
                case COUNT: // CAST(SUM( COUNT(x) ) AS BIGINT)
                    mapAgg = aggregate(agg.distinct(), agg.type());

                    if (agg.type() == COUNT)
                        mapAgg.addChild(agg.child());

                    rdcAgg = aggregate(false, SUM).addChild(column(mapAggAlias));

                    rdcAgg = function(CAST).setCastType("BIGINT").addChild(rdcAgg);

                    break;

                default:
                    throw new IgniteException("Unsupported aggregate: " + agg.type());
            }

            assert !(mapAgg instanceof GridSqlAlias);

            // Add generated alias to map aggregate.
            mapAgg = alias(mapAggAlias, mapAgg);

            if (alias != null) // Add initial alias if it was set.
                rdcAgg = alias(alias.alias(), rdcAgg);

            // Set map and reduce aggregates to their places in selects.
            mapSelect.set(idx, mapAgg);

            rdcSelect[idx] = rdcAgg;
        }
        else {
            if (alias == null) { // Generate alias if none.
                GridSqlElement expr = mapSelect.get(idx);

                String aliasName = expr instanceof GridSqlColumn ? ((GridSqlColumn)expr).columnName() :
                    columnName(idx);

                alias = alias(aliasName, expr);

                mapSelect.set(idx, alias);
            }

            if (idx < rdcSelect.length)
                rdcSelect[idx] = column(alias.alias());
        }
    }

    /**
     * @param distinct Distinct.
     * @param type Type.
     * @return Aggregate function.
     */
    private static GridSqlAggregateFunction aggregate(boolean distinct, GridSqlFunctionType type) {
        return new GridSqlAggregateFunction(distinct, type);
    }

    /**
     * @param name Column name.
     * @return Column.
     */
    private static GridSqlColumn column(String name) {
        return new GridSqlColumn(null, name, name);
    }

    /**
     * @param alias Alias.
     * @param child Child.
     * @return Alias.
     */
    private static GridSqlAlias alias(String alias, GridSqlElement child) {
        return new GridSqlAlias(alias, child);
    }

    /**
     * @param type Type.
     * @param left Left expression.
     * @param right Right expression.
     * @return Binary operator.
     */
    private static GridSqlOperation op(GridSqlOperationType type, GridSqlElement left, GridSqlElement right) {
        return new GridSqlOperation(type, left, right);
    }

    /**
     * @param type Type.
     * @return Function.
     */
    private static GridSqlFunction function(GridSqlFunctionType type) {
        return new GridSqlFunction(type);
    }

    /**
     * @param name Table name.
     * @return Table.
     */
    private static GridSqlTable table(String name) {
        return new GridSqlTable(null, name);
    }
}
