/*
 * Copyright (c) 2017 "Neo4j, Inc." <http://neo4j.com>
 *
 * This file is part of Neo4j Graph Algorithms <http://github.com/neo4j-contrib/neo4j-graph-algorithms>.
 *
 * Neo4j Graph Algorithms is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.algo.similarity;

import org.junit.*;
import org.neo4j.graphalgo.IsFiniteFunc;
import org.neo4j.graphalgo.TestDatabaseCreator;
import org.neo4j.graphalgo.similarity.CosineProc;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.kernel.api.exceptions.KernelException;
import org.neo4j.kernel.impl.proc.Procedures;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

import java.util.Collections;
import java.util.Map;

import static java.util.Collections.singletonMap;
import static org.junit.Assert.*;
import static org.neo4j.helpers.collection.MapUtil.map;

public class CosineTest {

    private static GraphDatabaseAPI db;
    private Transaction tx;
    public static final String STATEMENT_STREAM = "MATCH (i:Item) WITH i ORDER BY id(i) MATCH (p:Person) OPTIONAL MATCH (p)-[r:LIKES]->(i)\n" +
            "WITH {item:id(p), weights: collect(coalesce(r.stars,$missingValue))} as userData\n" +
            "WITH collect(userData) as data\n" +
            "call algo.similarity.cosine.stream(data,$config) " +
            "yield item1, item2, count1, count2, intersection, similarity " +
            "RETURN item1, item2, count1, count2, intersection, similarity " +
            "ORDER BY item1,item2";

    public static final String STATEMENT_CYPHER_STREAM = "call algo.similarity.cosine.stream($query,$config) " +
            "yield item1, item2, count1, count2, intersection, similarity " +
            "RETURN * ORDER BY item1,item2";

    public static final String STATEMENT = "MATCH (i:Item) WITH i ORDER BY id(i) MATCH (p:Person) OPTIONAL MATCH (p)-[r:LIKES]->(i)\n" +
            "WITH {item:id(p), weights: collect(coalesce(r.stars,0))} as userData\n" +
            "WITH collect(userData) as data\n" +

            "CALL algo.similarity.cosine(data, $config) " +
            "yield p25, p50, p75, p90, p95, p99, p999, p100, nodes, similarityPairs, computations " +
            "RETURN *";

    public static final String STORE_EMBEDDING_STATEMENT = "MATCH (i:Item) WITH i ORDER BY id(i) MATCH (p:Person) OPTIONAL MATCH (p)-[r:LIKES]->(i)\n" +
            "WITH p, collect(coalesce(r.stars,0)) as userData\n" +
            "SET p.embedding = userData";

    public static final String EMBEDDING_STATEMENT = "MATCH (p:Person)\n" +
            "WITH {item:id(p), weights: p.embedding} as userData\n" +
            "WITH collect(userData) as data\n" +

            "CALL algo.similarity.cosine(data, $config) " +
            "yield p25, p50, p75, p90, p95, p99, p999, p100, nodes, similarityPairs " +
            "RETURN *";

    @BeforeClass
    public static void beforeClass() throws KernelException {
        db = TestDatabaseCreator.createTestDatabase();
        Procedures procedures = db.getDependencyResolver().resolveDependency(Procedures.class);
        procedures.registerProcedure(CosineProc.class);
        procedures.registerFunction(IsFiniteFunc.class);
        db.execute(buildDatabaseQuery()).close();
    }

    @AfterClass
    public static void AfterClass() {
        db.shutdown();
    }

    @Before
    public void setUp() throws Exception {
        tx = db.beginTx();
    }

    @After
    public void tearDown() throws Exception {
        tx.close();
    }

    private static void buildRandomDB(int size) {
        db.execute("MATCH (n) DETACH DELETE n").close();
        db.execute("UNWIND range(1,$size/10) as _ CREATE (:Person) CREATE (:Item) ",singletonMap("size",size)).close();
        String statement =
                "MATCH (p:Person) WITH collect(p) as people " +
                "MATCH (i:Item) WITH people, collect(i) as items " +
                "UNWIND range(1,$size) as _ " +
                "WITH people[toInteger(rand()*size(people))] as p, items[toInteger(rand()*size(items))] as i " +
                "MERGE (p)-[:LIKES]->(i) RETURN count(*) ";
        db.execute(statement,singletonMap("size",size)).close();
    }
    private static String buildDatabaseQuery() {
        return  "CREATE (a:Person {name:'Alice'})\n" +
                "CREATE (b:Person {name:'Bob'})\n" +
                "CREATE (c:Person {name:'Charlie'})\n" +
                "CREATE (d:Person {name:'Dana'})\n" +
                "CREATE (i1:Item {name:'p1'})\n" +
                "CREATE (i2:Item {name:'p2'})\n" +
                "CREATE (i3:Item {name:'p3'})\n" +

                "CREATE" +
                " (a)-[:LIKES {stars:1}]->(i1),\n" +
                " (a)-[:LIKES {stars:2}]->(i2),\n" +
                " (a)-[:LIKES {stars:5}]->(i3),\n" +

                " (b)-[:LIKES {stars:1}]->(i1),\n" +
                " (b)-[:LIKES {stars:3}]->(i2),\n" +

                " (c)-[:LIKES {stars:4}]->(i3)\n";

        /*
            for (int i = 0; i < len; i++) {
             double weight1 = vector1[i];
             // if (weight1 == 0d) continue;
             double weight2 = vector2[i];
             // if (weight2 == 0d) continue;

             dotProduct += weight1 * weight2;
             xLength += weight1 * weight1;
             yLength += weight2 * weight2;
         }

         return dotProduct / Math.sqrt(xLength * yLength);

         */
        // a: 1,2,5 : xL 30
        // b: 1,3,0 : 10
        // c: 0,0,4 : 16
        // d: 0,0,0 : 0
        // a0 - b1: (1+6)/sqrt(30*10) = 0.4
        // a0 - c2: 20 / sqrt(30*16) = 0.91
        // a0 - d3: 0 / sqrt(30*0) = 0
        // b1 - c2: 0 / sqrt(10*16) = 0
        // b1 - d3: 0
        // c2 - d3: 0

    }


    @Test
    public void cosineSingleMultiThreadComparision() {
        int size = 333;
        buildRandomDB(size);
        Result result1 = db.execute(STATEMENT_STREAM, map("config", map("similarityCutoff",-0.1,"concurrency", 1), "missingValue", 0));
        Result result2 = db.execute(STATEMENT_STREAM, map("config", map("similarityCutoff",-0.1,"concurrency", 2), "missingValue", 0));
        Result result4 = db.execute(STATEMENT_STREAM, map("config", map("similarityCutoff",-0.1,"concurrency", 4), "missingValue", 0));
        Result result8 = db.execute(STATEMENT_STREAM, map("config", map("similarityCutoff",-0.1,"concurrency", 8), "missingValue", 0));
        int count=0;
        while (result1.hasNext()) {
            Map<String, Object> row1 = result1.next();
            assertEquals(row1.toString(), row1,result2.next());
            assertEquals(row1.toString(), row1,result4.next());
            assertEquals(row1.toString(), row1,result8.next());
            count++;
        }
        int people = size/10;
        assertEquals((people * people - people)/2,count);
    }

    @Test
    public void cosineSingleMultiThreadComparisionTopK() {
        int size = 333;
        buildRandomDB(size);

        Result result1 = db.execute(STATEMENT_STREAM, map("config", map("similarityCutoff",-0.1,"topK",1,"concurrency", 1), "missingValue", 0));
        Result result2 = db.execute(STATEMENT_STREAM, map("config", map("similarityCutoff",-0.1,"topK",1,"concurrency", 2), "missingValue", 0));
        Result result4 = db.execute(STATEMENT_STREAM, map("config", map("similarityCutoff",-0.1,"topK",1,"concurrency", 4), "missingValue", 0));
        Result result8 = db.execute(STATEMENT_STREAM, map("config", map("similarityCutoff",-0.1,"topK",1,"concurrency", 8), "missingValue", 0));
        int count=0;
        while (result1.hasNext()) {
            Map<String, Object> row1 = result1.next();
            assertEquals(row1.toString(), row1,result2.next());
            assertEquals(row1.toString(), row1,result4.next());
            assertEquals(row1.toString(), row1,result8.next());
            count++;
        }
        int people = size/10;
        assertEquals(people,count);
    }

    @Test
    public void topNcosineStreamTest() {
        Result results = db.execute(STATEMENT_STREAM, map("config",map("top",2), "missingValue", 0));
        assert01(results.next());
        assert02(results.next());
        assertFalse(results.hasNext());
    }

    @Test
    public void cosineStreamTest() {
        Result results = db.execute(STATEMENT_STREAM, map("config",map("concurrency",1), "missingValue", 0));
        assertTrue(results.hasNext());
        assert01(results.next());
        assert02(results.next());
        assert03(results.next());
        assert12(results.next());
        assert13(results.next());
        assert23(results.next());
        assertFalse(results.hasNext());
    }

    @Test
    public void cosineStreamSourceTargetIdsTest() {
        Map<String, Object> config = map(
                "concurrency", 1,
                "sourceIds", Collections.singletonList(0L),
                "targetIds", Collections.singletonList(1L)
        );
        Result results = db.execute(STATEMENT_STREAM, map("config", config, "missingValue", 0));

        assertTrue(results.hasNext());
        assert01(results.next());
        assertFalse(results.hasNext());
    }

    @Test
    public void cosineSkipStreamTest() {
        Result results = db.execute(STATEMENT_STREAM,
                map("config",map("concurrency",1, "skipValue", Double.NaN), "missingValue", Double.NaN));

        assertTrue(results.hasNext());
        assert01Skip(results.next());
        assert02Skip(results.next());
        assert12Skip(results.next());
        assertFalse(results.hasNext());
    }

    @Test
    public void cosineCypherLoadingStreamTest() {
        String query = "MATCH (p:Person)-[r:LIKES]->(i) RETURN id(p) AS item, id(i) AS category, r.stars AS weight";
        Result results = db.execute(STATEMENT_CYPHER_STREAM, map("config", map("concurrency", 1, "graph", "cypher", "skipValue", 0.0), "query", query));

        assertTrue(results.hasNext());
        assert01Skip(results.next());
        assert02Skip(results.next());
        assert12Skip(results.next());
        assertFalse(results.hasNext());
    }

    @Test
    public void topKCosineStreamTest() {
        Map<String, Object> params = map("config", map( "concurrency", 1,"topK", 1), "missingValue", 0);
        System.out.println(db.execute(STATEMENT_STREAM, params).resultAsString());
        Result results = db.execute(STATEMENT_STREAM, params);
        assertTrue(results.hasNext());
        assert02(results.next());
        assert01(flip(results.next()));
        assert02(flip(results.next()));
        assert03(flip(results.next()));
        assertFalse(results.hasNext());
    }

    @Test
    public void topKCosineSourceTargetIdStreamTest() {
        Map<String, Object> config = map(
                "concurrency", 1,
                "topK", 1,
                "sourceIds", Collections.singletonList(0L)

        );
        Map<String, Object> params = map("config", config, "missingValue", 0);
        System.out.println(db.execute(STATEMENT_STREAM, params).resultAsString());
        Result results = db.execute(STATEMENT_STREAM, params);
        assertTrue(results.hasNext());
        assert02(results.next());
        assertFalse(results.hasNext());
    }

    private Map<String, Object> flip(Map<String, Object> row) {
        return map("similarity", row.get("similarity"),"intersection", row.get("intersection"),
                "item1",row.get("item2"),"count1",row.get("count2"),
                "item2",row.get("item1"),"count2",row.get("count1"));
    }

    private void assertSameSource(Result results, int count, long source) {
        Map<String, Object> row;
        long target = 0;
        for (int i = 0; i<count; i++) {
            if (target == source) target++;
            assertTrue(results.hasNext());
            row = results.next();
            assertEquals(source, row.get("item1"));
            assertEquals(target, row.get("item2"));
            target++;
        }
    }


    @Test
    public void topK4cosineStreamTest() {
        Map<String, Object> params = map("config", map("topK", 4, "concurrency", 4, "similarityCutoff", -0.1), "missingValue", 0);

        Result results = db.execute(STATEMENT_STREAM,params);
        assertSameSource(results, 3, 0L);
        assertSameSource(results, 3, 1L);
        assertSameSource(results, 3, 2L);
        assertSameSource(results, 3, 3L);
        assertFalse(results.hasNext());
    }

    @Test
    public void topK3cosineStreamTest() {
        Map<String, Object> params = map("config", map("concurrency", 3, "topK", 3), "missingValue", 0);

        System.out.println(db.execute(STATEMENT_STREAM, params).resultAsString());

        Result results = db.execute(STATEMENT_STREAM, params);
        assertSameSource(results, 3, 0L);
        assertSameSource(results, 3, 1L);
        assertSameSource(results, 3, 2L);
        assertSameSource(results, 3, 3L);
        assertFalse(results.hasNext());
    }

    @Test
    public void simpleCosineTest() {
        Map<String, Object> params = map("config", map());

        Map<String, Object> row = db.execute(STATEMENT,params).next();
        assertEquals((double) row.get("p25"), 0.0, 0.01);
        assertEquals((double) row.get("p50"), 0, 0.01);
        assertEquals((double) row.get("p75"), 0.40, 0.01);
        assertEquals((double) row.get("p90"), 0.40, 0.01);
        assertEquals((double) row.get("p95"), 0.91, 0.01);
        assertEquals((double) row.get("p99"), 0.91, 0.01);
        assertEquals((double) row.get("p100"), 0.91, 0.01);
    }

    @Test
    public void simpleCosineFromEmbeddingTest() {
        db.execute(STORE_EMBEDDING_STATEMENT);

        Map<String, Object> params = map("config", map());

        Map<String, Object> row = db.execute(EMBEDDING_STATEMENT,params).next();
        assertEquals((double) row.get("p25"), 0.0, 0.01);
        assertEquals((double) row.get("p50"), 0, 0.01);
        assertEquals((double) row.get("p75"), 0.40, 0.01);
        assertEquals((double) row.get("p90"), 0.40, 0.01);
        assertEquals((double) row.get("p95"), 0.91, 0.01);
        assertEquals((double) row.get("p99"), 0.91, 0.01);
        assertEquals((double) row.get("p100"), 0.91, 0.01);
    }

    @Test
    public void dontComputeComputationsByDefault() {
        Map<String, Object> params = map("config", map(
                "write", true,
                "similarityCutoff", 0.1));

        Result writeResult = db.execute(STATEMENT, params);
        Map<String, Object> writeRow = writeResult.next();
        assertEquals(-1L, (long) writeRow.get("computations"));
    }

    @Test
    public void numberOfComputations() {
        Map<String, Object> params = map("config", map(
                "write", true,
                "showComputations", true,
                "similarityCutoff", 0.1));

        Result writeResult = db.execute(STATEMENT, params);
        Map<String, Object> writeRow = writeResult.next();
        assertEquals(6L, (long) writeRow.get("computations"));
    }

    @Test
    public void simpleCosineWriteTest() {
        Map<String, Object> params = map("config", map( "write",true, "similarityCutoff", 0.1));

        db.execute(STATEMENT, params).close();

        String checkSimilaritiesQuery = "MATCH (a)-[similar:SIMILAR]-(b)" +
                "RETURN a.name AS node1, b.name as node2, similar.score AS score " +
                "ORDER BY id(a), id(b)";

        System.out.println(db.execute(checkSimilaritiesQuery).resultAsString());
        Result result = db.execute(checkSimilaritiesQuery);

        assertTrue(result.hasNext());
        Map<String, Object> row = result.next();
        assertEquals(row.get("node1"), "Alice");
        assertEquals(row.get("node2"), "Bob");
        assertEquals((double) row.get("score"), 0.40, 0.01);

        assertTrue(result.hasNext());
        row = result.next();
        assertEquals(row.get("node1"), "Alice");
        assertEquals(row.get("node2"), "Charlie");
        assertEquals((double) row.get("score"), 0.91, 0.01);

        assertTrue(result.hasNext());
        row = result.next();
        assertEquals(row.get("node1"), "Bob");
        assertEquals(row.get("node2"), "Alice");
        assertEquals((double) row.get("score"), 0.40, 0.01);


        assertTrue(result.hasNext());
        row = result.next();
        assertEquals(row.get("node1"), "Charlie");
        assertEquals(row.get("node2"), "Alice");
        assertEquals((double) row.get("score"), 0.91, 0.01);

        assertFalse(result.hasNext());
    }

    private void assert23(Map<String, Object> row) {
        assertEquals(2L, row.get("item1"));
        assertEquals(3L, row.get("item2"));
        assertEquals(3L, row.get("count1"));
        assertEquals(3L, row.get("count2"));
        assertEquals(0L, row.get("intersection"));
        assertEquals(0d, row.get("similarity"));
    }

    private void assert23Skip(Map<String, Object> row) {
        assertEquals(2L, row.get("item1"));
        assertEquals(3L, row.get("item2"));
        assertEquals(1L, row.get("count1"));
        assertEquals(0L, row.get("count2"));
        assertEquals(0L, row.get("intersection"));
        assertEquals(0d, row.get("similarity"));
    }

    private void assert13(Map<String, Object> row) {
        assertEquals(1L, row.get("item1"));
        assertEquals(3L, row.get("item2"));
        assertEquals(3L, row.get("count1"));
        assertEquals(3L, row.get("count2"));
        assertEquals(0L, row.get("intersection"));
        assertEquals(0d, row.get("similarity"));
    }

    private void assert13Skip(Map<String, Object> row) {
        assertEquals(1L, row.get("item1"));
        assertEquals(3L, row.get("item2"));
        assertEquals(2L, row.get("count1"));
        assertEquals(0L, row.get("count2"));
        assertEquals(0L, row.get("intersection"));
        assertEquals(0d, row.get("similarity"));
    }

    private void assert12(Map<String, Object> row) {
        assertEquals(1L, row.get("item1"));
        assertEquals(2L, row.get("item2"));
        assertEquals(3L, row.get("count1"));
        assertEquals(3L, row.get("count2"));
        // assertEquals(0L, row.get("intersection"));
        assertEquals(0.0, row.get("similarity"));
    }

    private void assert12Skip(Map<String, Object> row) {
        assertEquals(1L, row.get("item1"));
        assertEquals(2L, row.get("item2"));
        assertEquals(2L, row.get("count1"));
        assertEquals(1L, row.get("count2"));
        // assertEquals(0L, row.get("intersection"));
        assertEquals(0.0, row.get("similarity"));
    }

    private void assert03(Map<String, Object> row) {
        assertEquals(0L, row.get("item1"));
        assertEquals(3L, row.get("item2"));
        assertEquals(3L, row.get("count1"));
        assertEquals(3L, row.get("count2"));
        assertEquals(0L, row.get("intersection"));
        assertEquals(0d, row.get("similarity"));
    }

    private void assert03Skip(Map<String, Object> row) {
        assertEquals(0L, row.get("item1"));
        assertEquals(3L, row.get("item2"));
        assertEquals(3L, row.get("count1"));
        assertEquals(0L, row.get("count2"));
        assertEquals(0L, row.get("intersection"));
        assertEquals(0d, row.get("similarity"));
    }

    private void assert02(Map<String, Object> row) {
        assertEquals(0L, row.get("item1"));
        assertEquals(2L, row.get("item2"));
        assertEquals(3L, row.get("count1"));
        assertEquals(3L, row.get("count2"));
        // assertEquals(1L, row.get("intersection"));
        assertEquals(0.91, (double)row.get("similarity"),0.01);
    }

    private void assert02Skip(Map<String, Object> row) {
        assertEquals(0L, row.get("item1"));
        assertEquals(2L, row.get("item2"));
        assertEquals(3L, row.get("count1"));
        assertEquals(1L, row.get("count2"));
        // assertEquals(1L, row.get("intersection"));
        assertEquals(1.0, (double)row.get("similarity"),0.01);
    }

    private void assert01(Map<String, Object> row) {
        assertEquals(0L, row.get("item1"));
        assertEquals(1L, row.get("item2"));
        assertEquals(3L, row.get("count1"));
        assertEquals(3L, row.get("count2"));
        // assertEquals(2L, row.get("intersection"));
        assertEquals(0.40, (double)row.get("similarity"),0.01);
    }

    private void assert01Skip(Map<String, Object> row) {
        assertEquals(0L, row.get("item1"));
        assertEquals(1L, row.get("item2"));
        assertEquals(3L, row.get("count1"));
        assertEquals(2L, row.get("count2"));
        // assertEquals(2L, row.get("intersection"));
        assertEquals(0.98, (double)row.get("similarity"),0.01);
    }
}
