package com.flipkart.hbaseobjectmapper.testcases;

import com.flipkart.hbaseobjectmapper.HBRecord;
import com.flipkart.hbaseobjectmapper.WrappedHBColumn;
import com.flipkart.hbaseobjectmapper.testcases.daos.*;
import com.flipkart.hbaseobjectmapper.testcases.entities.Citizen;
import com.flipkart.hbaseobjectmapper.testcases.entities.Crawl;
import com.flipkart.hbaseobjectmapper.testcases.entities.CrawlNoVersion;
import com.flipkart.hbaseobjectmapper.testcases.entities.Employee;
import com.flipkart.hbaseobjectmapper.testcases.util.cluster.HBaseCluster;
import com.flipkart.hbaseobjectmapper.testcases.util.cluster.InMemoryHBaseCluster;
import com.flipkart.hbaseobjectmapper.testcases.util.cluster.RealHBaseCluster;
import org.apache.hadoop.conf.Configuration;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;

import static com.flipkart.hbaseobjectmapper.testcases.util.LiteralsUtil.a;
import static com.flipkart.hbaseobjectmapper.testcases.util.LiteralsUtil.s;
import static org.junit.Assert.*;

public class TestsAbstractHBDAO {
    private static Configuration configuration;
    private static HBaseCluster hBaseCluster;

    @BeforeClass
    public static void setup() {
        try {
            String useRegularHBaseClient = System.getenv("USE_REGULAR_HBASE_CLIENT");
            if (useRegularHBaseClient != null && (useRegularHBaseClient.equals("1") || useRegularHBaseClient.equalsIgnoreCase("true")))
                hBaseCluster = new RealHBaseCluster();
            else
                hBaseCluster = new InMemoryHBaseCluster();
            configuration = hBaseCluster.init();
        } catch (Exception e) {
            e.printStackTrace();
            fail("Failed to connect to HBase. Aborted execution of DAO-related test cases");
        }
    }

    @Test
    public void testRegularCRUD() throws IOException {
        hBaseCluster.createTable("citizens", a("main", "optional"), 1);
        hBaseCluster.createTable("citizens_summary", a("a"), 1);
        try (
                CitizenDAO citizenDao = new CitizenDAO(configuration);
                CitizenSummaryDAO citizenSummaryDAO = new CitizenSummaryDAO(configuration);
        ) {
            Assert.assertEquals(citizenDao.getTableName(), "citizens");
            assertEquals("Issue with column families of 'citizens' table\n" + citizenDao.getColumnFamilies(), s("main", "optional"), citizenDao.getColumnFamilies());
            assertEquals(citizenSummaryDAO.getTableName(), "citizens_summary");
            assertEquals("Issue with column families of 'citizens_summary' table\n" + citizenSummaryDAO.getColumnFamilies(), s("a"), citizenSummaryDAO.getColumnFamilies());
            final List<Citizen> testObjs = TestObjects.validCitizenObjectsNoVersion;
            String[] rowKeys = new String[testObjs.size()];
            Map<String, Map<String, Object>> expectedFieldValues = new HashMap<>();
            for (int i = 0; i < testObjs.size(); i++) {
                HBRecord<String> e = testObjs.get(i);
                try {
                    final String rowKey = citizenDao.persist(e);
                    rowKeys[i] = rowKey;
                    Citizen pe = citizenDao.get(rowKey);
                    assertEquals("Entry got corrupted upon persisting and fetching back", e, pe);
                    for (String f : citizenDao.getFields()) {
                        try {
                            Field field = Citizen.class.getDeclaredField(f);
                            WrappedHBColumn hbColumn = new WrappedHBColumn(field);
                            field.setAccessible(true);
                            final Object actual = citizenDao.fetchFieldValue(rowKey, f);
                            Object expected = field.get(e);
                            if (hbColumn.isMultiVersioned()) {
                                NavigableMap columnHistory = ((NavigableMap) field.get(e));
                                if (columnHistory != null && columnHistory.size() > 0) {
                                    expected = columnHistory.lastEntry().getValue();
                                }
                            }
                            assertEquals("Field data corrupted upon persisting and fetching back", expected, actual);
                            if (actual == null) continue;
                            if (!expectedFieldValues.containsKey(f)) {
                                expectedFieldValues.put(f, new HashMap<String, Object>() {
                                    {
                                        put(rowKey, actual);
                                    }
                                });
                            } else {
                                expectedFieldValues.get(f).put(rowKey, actual);
                            }
                        } catch (IllegalAccessException e1) {
                            e1.printStackTrace();
                            fail("Can't get field " + f + " from object " + e);
                        } catch (NoSuchFieldException e1) {
                            e1.printStackTrace();
                            fail("Field missing: " + f);
                        } catch (IOException ioex) {
                            ioex.printStackTrace();
                            fail("Could not fetch field '" + f + "' for row '" + rowKey + "'");
                        }
                    }
                } catch (IOException ioex) {
                    ioex.printStackTrace();
                    fail();
                }
            }
            List<Citizen> citizens = citizenDao.get(rowKeys[0], rowKeys[rowKeys.length - 1]);
            for (int i = 0; i < citizens.size(); i++) {
                assertEquals("When retrieved in bulk (range scan), we have unexpected entry", citizens.get(i), testObjs.get(i));
            }
            for (String f : citizenDao.getFields()) {
                Map<String, Object> actualFieldValues = citizenDao.fetchFieldValues(rowKeys, f);
                Map<String, Object> actualFieldValuesScanned = citizenDao.fetchFieldValues("A", "z", f);
                assertEquals(String.format("Invalid data returned when values for column \"%s\" were fetched in bulk\nExpected: %s\nActual: %s", f, expectedFieldValues.get(f), actualFieldValues), expectedFieldValues.get(f), actualFieldValues);
                assertEquals("Difference between 'bulk fetch by array of row keys' and 'bulk fetch by range of row keys'", actualFieldValues, actualFieldValuesScanned);
            }
            Map<String, Object> actualSalaries = citizenDao.fetchFieldValues(rowKeys, "sal");
            long actualSumOfSalaries = 0;
            for (Object s : actualSalaries.values()) {
                actualSumOfSalaries += s == null ? 0 : (Integer) s;
            }
            long expectedSumOfSalaries = 0;
            for (Citizen c : testObjs) {
                expectedSumOfSalaries += c.getSal() == null ? 0 : c.getSal();
            }
            assertEquals(expectedSumOfSalaries, actualSumOfSalaries);
            Assert.assertArrayEquals("Data mismatch between single and bulk 'get' calls", testObjs.toArray(), citizenDao.get(rowKeys));
            Assert.assertEquals("Data mismatch between List and array bulk variants of 'get' calls", testObjs, citizenDao.get(Arrays.asList(rowKeys)));
            Citizen citizenToBeDeleted = testObjs.get(0);
            citizenDao.delete(citizenToBeDeleted);
            assertNull("Record was not deleted: " + citizenToBeDeleted, citizenDao.get(citizenToBeDeleted.composeRowKey()));
            List<Citizen> citizensToBeDeleted = Arrays.asList(testObjs.get(1), testObjs.get(2));
            citizenDao.delete(citizensToBeDeleted);
            assertNull("Record was not deleted: " + citizensToBeDeleted.get(0), citizenDao.get(citizensToBeDeleted.get(0).composeRowKey()));
            assertNull("Record was not deleted: " + citizensToBeDeleted.get(1), citizenDao.get(citizensToBeDeleted.get(1).composeRowKey()));
        }
    }

    @Test
    public void testMultiVersionCRUD() throws Exception {
        hBaseCluster.createTable("crawls", a("a"), 3);
        try (
                CrawlDAO crawlDAO = new CrawlDAO(configuration);
                CrawlNoVersionDAO crawlNoVersionDAO = new CrawlNoVersionDAO(configuration);
        ) {
            final int NUM_VERSIONS = 3;
            Double[] testNumbers = new Double[]{-1.0, Double.MAX_VALUE, Double.MIN_VALUE, 3.14159, 2.71828, 1.0};
            Double[] testNumbersOfRange = Arrays.copyOfRange(testNumbers, testNumbers.length - NUM_VERSIONS, testNumbers.length);
            // Written as unversioned, read as versioned
            List<CrawlNoVersion> objs = new ArrayList<>();
            for (Double n : testNumbers) {
                objs.add(new CrawlNoVersion("key").setF1(n));
            }
            crawlNoVersionDAO.persist(objs);
            Crawl crawl = crawlDAO.get("key", NUM_VERSIONS);
            assertEquals("Issue with version history implementation when written as unversioned and read as versioned", 1.0, crawl.getF1().values().iterator().next(), 1e-9);
            crawlDAO.delete("key");
            Crawl versioned = crawlDAO.get("key");
            assertNull("Deleted row (with key " + versioned + ") still exists when accessed as versioned DAO", versioned);
            CrawlNoVersion versionless = crawlNoVersionDAO.get("key");
            assertNull("Deleted row (with key " + versionless + ") still exists when accessed as versionless DAO", versionless);
            // Written as versioned, read as unversioned+versioned
            Crawl crawl2 = new Crawl("key2");
            long timestamp = System.currentTimeMillis();
            long i = 0;
            for (Double n : testNumbers) {
                crawl2.addF1(timestamp + i, n);
                i++;
            }
            crawlDAO.persist(crawl2);
            CrawlNoVersion crawlNoVersion = crawlNoVersionDAO.get("key2");
            assertEquals("Entry with the highest version (i.e. timestamp) isn't the one that was returned by DAO get", crawlNoVersion.getF1(), testNumbers[testNumbers.length - 1]);
            assertArrayEquals("Issue with version history implementation when written as versioned and read as unversioned", testNumbersOfRange, crawlDAO.get("key2", NUM_VERSIONS).getF1().values().toArray());

            List<String> rowKeysList = new ArrayList<>();
            for (int v = 0; v <= 9; v++) {
                for (int k = 1; k <= 4; k++) {
                    String key = "oKey" + k;
                    crawlDAO.persist(new Crawl(key).addF1((double) v));
                    rowKeysList.add(key);
                }
            }
            String[] rowKeys = rowKeysList.toArray(new String[rowKeysList.size()]);

            Set<Double> oldestValuesRangeScan = new HashSet<>(), oldestValuesBulkScan = new HashSet<>();
            for (int k = 1; k <= NUM_VERSIONS; k++) {
                Set<Double> latestValuesRangeScan = new HashSet<>();
                NavigableMap<String, NavigableMap<Long, Object>> fieldValues1 = crawlDAO.fetchFieldValues("oKey0", "oKey9", "f1", k);
                for (NavigableMap.Entry<String, NavigableMap<Long, Object>> e : fieldValues1.entrySet()) {
                    latestValuesRangeScan.add((Double) e.getValue().lastEntry().getValue());
                    oldestValuesRangeScan.add((Double) e.getValue().firstEntry().getValue());
                }
                assertEquals("When fetching multiple versions of a field, the latest version of field is not as expected", 1, latestValuesRangeScan.size());
                Set<Double> latestValuesBulkScan = new HashSet<>();
                Map<String, NavigableMap<Long, Object>> fieldValues2 = crawlDAO.fetchFieldValues(rowKeys, "f1", k);
                for (NavigableMap.Entry<String, NavigableMap<Long, Object>> e : fieldValues2.entrySet()) {
                    latestValuesBulkScan.add((Double) e.getValue().lastEntry().getValue());
                    oldestValuesBulkScan.add((Double) e.getValue().firstEntry().getValue());
                }
                assertEquals("When fetching multiple versions of a field, the latest version of field is not as expected", 1, latestValuesBulkScan.size());
            }
            assertEquals("When fetching multiple versions of a field through bulk scan, the oldest version of field is not as expected", NUM_VERSIONS, oldestValuesRangeScan.size());
            assertEquals("When fetching multiple versions of a field through range scan, the oldest version of field is not as expected", NUM_VERSIONS, oldestValuesBulkScan.size());
            assertEquals("Fetch by array and fetch by range differ", oldestValuesRangeScan, oldestValuesBulkScan);

            // Deletion tests:

            // Written as unversioned, deleted as unversioned:
            final String deleteKey1 = "write_unversioned__delete_unversioned";
            crawlNoVersionDAO.persist(new Crawl(deleteKey1).addF1(10.01));
            crawlNoVersionDAO.delete(deleteKey1);
            assertNull("Row with key '" + deleteKey1 + "' exists, when written through unversioned DAO and deleted through unversioned DAO!", crawlNoVersionDAO.get(deleteKey1));

            // Written as versioned, deleted as versioned:
            final String deleteKey2 = "write_versioned__delete_versioned";
            crawlDAO.persist(new Crawl(deleteKey2).addF1(10.02));
            crawlDAO.delete(deleteKey2);
            assertNull("Row with key '" + deleteKey2 + "' exists, when written through versioned DAO and deleted through versioned DAO!", crawlNoVersionDAO.get(deleteKey2));

            // Written as unversioned, deleted as versioned:
            final String deleteKey3 = "write_unversioned__delete_versioned";
            crawlNoVersionDAO.persist(new Crawl(deleteKey3).addF1(10.03));
            crawlDAO.delete(deleteKey3);
            assertNull("Row with key '" + deleteKey3 + "' exists, when written through unversioned DAO and deleted through versioned DAO!", crawlNoVersionDAO.get(deleteKey3));

            // Written as versioned, deleted as unversioned:
            final String deleteKey4 = "write_versioned__delete_unversioned";
            crawlDAO.persist(new Crawl(deleteKey4).addF1(10.04));
            crawlNoVersionDAO.delete(deleteKey4);
            assertNull("Row with key '" + deleteKey4 + "' exists, when written through versioned DAO and deleted through unversioned DAO!", crawlNoVersionDAO.get(deleteKey4));
        }

    }

    @Test
    public void testNonStringRowkeys() throws IOException {
        hBaseCluster.createTable("employees", a("a"), 1);
        try (
                EmployeeDAO employeeDAO = new EmployeeDAO(configuration);
        ) {
            Employee ePre = new Employee(100L, "E1", (short) 3);
            Long rowKey = employeeDAO.persist(ePre);
            Employee ePost = employeeDAO.get(rowKey);
            assertEquals("Object got corrupted ", ePre, ePost);
        }
    }

    @AfterClass
    public static void tearDown() throws Exception {
        hBaseCluster.end();
    }
}
