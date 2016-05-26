package com.flipkart.hbaseobjectmapper.mr;

import com.flipkart.hbaseobjectmapper.HBObjectMapper;
import com.flipkart.hbaseobjectmapper.Util;
import com.flipkart.hbaseobjectmapper.entities.CitizenSummary;
import com.flipkart.hbaseobjectmapper.mr.lib.TableReduceDriver;
import com.flipkart.hbaseobjectmapper.mr.samples.CitizenReducer;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mrunit.types.Pair;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class TestReducer {

    HBObjectMapper hbObjectMapper = new HBObjectMapper();
    TableReduceDriver<ImmutableBytesWritable, IntWritable, ImmutableBytesWritable> reducerDriver;

    @Before
    public void setUp() throws Exception {
        reducerDriver = new TableReduceDriver<ImmutableBytesWritable, IntWritable, ImmutableBytesWritable>();
    }

    public void test() throws Exception {
        Pair<ImmutableBytesWritable, Writable> reducerResult = reducerDriver.withInput(Util.strToIbw("key"), Arrays.asList(new IntWritable(1), new IntWritable(5))).run().get(0);
        CitizenSummary citizenSummary = hbObjectMapper.readValue(reducerResult.getFirst(), (Put) reducerResult.getSecond(), CitizenSummary.class);
        assertEquals(citizenSummary.getAverageAge(), (Float) 3.0f);
    }
}
