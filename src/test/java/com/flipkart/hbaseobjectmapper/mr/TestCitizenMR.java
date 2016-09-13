package com.flipkart.hbaseobjectmapper.mr;

import com.flipkart.hbaseobjectmapper.TestObjects;
import com.flipkart.hbaseobjectmapper.entities.Citizen;
import com.flipkart.hbaseobjectmapper.entities.CitizenSummary;
import com.flipkart.hbaseobjectmapper.mr.samples.CitizenMapper;
import com.flipkart.hbaseobjectmapper.mr.samples.CitizenReducer;
import com.flipkart.hbaseobjectmapper.util.mr.AbstractMRTest;
import com.flipkart.hbaseobjectmapper.util.mr.TableMapDriver;
import com.flipkart.hbaseobjectmapper.util.mr.TableReduceDriver;
import com.flipkart.hbaseobjectmapper.util.mr.MRTestUtil;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mrunit.types.Pair;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class TestCitizenMR extends AbstractMRTest {

    private TableMapDriver<ImmutableBytesWritable, IntWritable> citizenMapDriver;
    private TableReduceDriver<ImmutableBytesWritable, IntWritable, ImmutableBytesWritable> citizenReduceDriver;

    @Before
    public void setUp() {
        citizenMapDriver = createMapDriver(new CitizenMapper());
        citizenReduceDriver = createReduceDriver(new CitizenReducer());
    }


    @Test
    public void testSingleMapper() throws IOException {
        Citizen citizen = (Citizen) TestObjects.validCitizenObjects.get(0);
        org.apache.hadoop.hbase.util.Pair<ImmutableBytesWritable, Result> rowKeyResultPair = hbObjectMapper.writeValueAsRowKeyResultPair(citizen);
        citizenMapDriver
                .withInput(
                        rowKeyResultPair.getFirst(), // this line can alternatively be hbObjectMapper.getRowKey(citizen)
                        rowKeyResultPair.getSecond() // this line can alternatively be hbObjectMapper.writeValueAsResult(citizen)

                )
                .withOutput(hbObjectMapper.rowKeyToIbw("key"), new IntWritable(citizen.getAge()))
                .runTest();
    }

    @Test
    public void testMultipleMappers() throws IOException {
        List<Pair<ImmutableBytesWritable, Result>> hbRecords = MRTestUtil.writeValueAsRowKeyResultPair(TestObjects.validCitizenObjects);
        List<Pair<ImmutableBytesWritable, IntWritable>> mapResults = citizenMapDriver.withAll(hbRecords).run();
        for (Pair<ImmutableBytesWritable, IntWritable> mapResult : mapResults) {
            assertEquals("Rowkey got corrupted in Mapper", Bytes.toString(mapResult.getFirst().get()), "key");
        }
    }

    @Test
    public void testReducer() throws Exception {
        Pair<ImmutableBytesWritable, Mutation> reducerResult = citizenReduceDriver.withInput(hbObjectMapper.rowKeyToIbw("key"), Arrays.asList(new IntWritable(1), new IntWritable(5))).run().get(0);
        CitizenSummary citizenSummary = hbObjectMapper.readValue(reducerResult.getFirst(), (Put) reducerResult.getSecond(), CitizenSummary.class);
        assertEquals("Unexpected result from CitizenReducer", (Float) 3.0f, citizenSummary.getAverageAge());
    }
}
