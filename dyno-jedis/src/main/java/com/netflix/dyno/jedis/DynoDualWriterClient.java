/**
 * Copyright 2016 Netflix, Inc.
 *
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
package com.netflix.dyno.jedis;

import com.netflix.dyno.connectionpool.ConnectionPool;
import com.netflix.dyno.connectionpool.OperationResult;
import com.netflix.dyno.contrib.DynoOPMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client that provides 'dual-write' functionality. This is useful when clients wish to move from one dynomite
 * cluster to another, for example to upgrade cluster capacity.
 *
 * @author jcacciatore
 */
public class DynoDualWriterClient extends DynoJedisClient {

    private static final Logger logger = LoggerFactory.getLogger(DynoDualWriterClient.class);

    private static ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());



    private final DynoJedisClient targetClient;
    private final Dial dial;

    public DynoDualWriterClient(String name, String clusterName,
                                ConnectionPool<Jedis> pool,
                                DynoOPMonitor operationMonitor,
                                DynoJedisClient targetClient) {

        this(name, clusterName, pool, operationMonitor, targetClient,
                new TimestampDial(pool.getConfiguration().getDualWritePercentage()));
    }

    public DynoDualWriterClient(String name, String clusterName,
                                ConnectionPool<Jedis> pool,
                                DynoOPMonitor operationMonitor,
                                DynoJedisClient targetClient,
                                Dial dial) {
        super(name, clusterName, pool, operationMonitor);
        this.targetClient = targetClient;
        this.dial = dial;
    }

    public Dial getDial() {
        return dial;
    }

    private <R> Future<OperationResult<R>> writeAsync(final String key, Callable<OperationResult<R>> func) {
        if (sendShadowRequest(key)) {
            try {
                return executor.submit(func);
            } catch (Throwable th) {
                opMonitor.recordFailure("shadowPool_submit", th.getMessage());
            }

            // if we need to do any other processing (logging, etc) now's the time...

        }

        return null;
    }
    
    /**
     *  writeAsync() for binary commands
     */
    private <R> Future<OperationResult<R>> writeAsync(final byte[] key, Callable<OperationResult<R>> func) {
        if (sendShadowRequest(key)) {
            try {
                return executor.submit(func);
            } catch (Throwable th) {
                opMonitor.recordFailure("shadowPool_submit", th.getMessage());
            }

            // if we need to do any other processing (logging, etc) now's the time...

        }

        return null;
    }
    

    /**
     * Returns true if the connection pool
     * <li>Is NOT idle</li>
     * <li>Has active pools (the shadow cluster may disappear at any time and we don't want to bloat logs)</li>
     * <li>The key is in range in the dial</li>
     * <p>
     * The idle check is necessary since there may be active host pools however the shadow client may not be able to
     * connect to them, for example, if security groups are not configured properly.
     */
    private boolean sendShadowRequest(String key) {
        return  this.getConnPool().getConfiguration().isDualWriteEnabled() &&
                !this.getConnPool().isIdle() &&
                this.getConnPool().getActivePools().size() > 0 &&
                dial.isInRange(key);
    }
    
    private boolean sendShadowRequest(byte[] key) {
        return  this.getConnPool().getConfiguration().isDualWriteEnabled() &&
                !this.getConnPool().isIdle() &&
                this.getConnPool().getActivePools().size() > 0 &&
                dial.isInRange(key);
    }

    public interface Dial {
        /**
         * Returns true if the given value is in range, false otherwise
         */
        boolean isInRange(String key);

        boolean isInRange(byte[] key);

        void setRange(int range);
    }

    /**
     * Default Dial implementation that presumes no knowledge of the key value
     * and simply uses a timestamp to determine inclusion/exclusion
     */
    private static class TimestampDial implements Dial {

        private final AtomicInteger range = new AtomicInteger(1);

        public TimestampDial(int range) {
            this.range.set(range);
        }

        @Override
        public boolean isInRange(String key) {
            return range.get() >  (System.currentTimeMillis() % 100);
        }
        
        @Override
        public boolean isInRange(byte[] key) {
            return range.get() >  (System.currentTimeMillis() % 100);
        }

        @Override
        public void setRange(int range) {
            this.range.set(range);
        }
    }   
    

    //----------------------------- JEDIS COMMANDS --------------------------------------

    @Override
    public Long append(final String key, final String value) {
        return this.d_append(key, value).getResult();
    }

    @Override
    public OperationResult<Long> d_append(final String key, final String value) {
        writeAsync(key, new Callable<OperationResult<Long>>() {
            @Override
            public OperationResult<Long> call() throws Exception {
                return DynoDualWriterClient.super.d_append(key, value);
            }
        });

        return targetClient.d_append(key, value);
    }

    @Override
    public String hmset(final String key, final Map<String, String> hash) {
        return this.d_hmset(key, hash).getResult();
    }

    @Override
    public OperationResult<String> d_hmset(final String key, final Map<String, String> hash) {
        writeAsync(key, new Callable<OperationResult<String>>(){
            @Override
            public OperationResult<String> call() throws Exception {
                return DynoDualWriterClient.super.d_hmset(key, hash);
            }
        });

        return targetClient.d_hmset(key, hash);
    }

    @Override
    public Long sadd(final String key, final String... members) {
        return this.d_sadd(key, members).getResult();
    }

    @Override
    public OperationResult<Long> d_sadd(final String key, final String... members) {
        writeAsync(key, new Callable<OperationResult<Long>>() {
            @Override
            public OperationResult<Long> call() throws Exception {
                return DynoDualWriterClient.super.d_sadd(key, members);
            }
        });

        return targetClient.d_sadd(key, members);

    }

    @Override
    public Long hset(final String key, final String field, final String value) {
        return this.d_hset(key, field, value).getResult();
    }

    @Override
    public OperationResult<Long> d_hset(final String key, final String field, final String value) {
        writeAsync(key, new Callable<OperationResult<Long>>() {
            @Override
            public OperationResult<Long> call() throws Exception {
                return DynoDualWriterClient.super.d_hset(key, field, value);
            }
        });

        return targetClient.d_hset(key, field, value);
    }

    @Override
    public String set(final String key, final String value) {
        return this.d_set(key, value).getResult();
    }

    @Override
    public OperationResult<String> d_set(final String key, final String value) {
        writeAsync(key, new Callable<OperationResult<String>>() {
            @Override
            public OperationResult<String> call() throws Exception {
                return DynoDualWriterClient.super.d_set(key, value);
            }
        });

        return targetClient.d_set(key, value);
    }

    @Override
    public String setex(final String key, int seconds, String value) {
        return this.d_setex(key, seconds, value).getResult();
    }

    @Override
    public OperationResult<String> d_setex(final String key, final Integer seconds, final String value) {
        writeAsync(key, new Callable<OperationResult<String>>(){
            @Override
            public OperationResult<String> call() throws Exception {
                return DynoDualWriterClient.super.d_setex(key, seconds, value);
            }
        });

        return targetClient.d_setex(key, seconds, value);

    }
    
    @Override
    public Long del(final String key) {
    	return this.d_del(key).getResult();
    }

    @Override
    public OperationResult<Long> d_del(final String key) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
                return DynoDualWriterClient.super.d_del(key);
            }
        });

        return targetClient.d_del(key);
    }
    
    @Override
    public Boolean exists(final String key) {
    	return this.d_exists(key).getResult();

    }
    
    @Override
    public OperationResult<Boolean> d_exists(final String key) {
        writeAsync(key, new Callable<OperationResult<Boolean>>(){
            @Override
            public OperationResult<Boolean> call() throws Exception {
            	 return DynoDualWriterClient.super.d_exists(key);
            }
        });

        return targetClient.d_exists(key);
    }
    
    @Override
    public Long expire(final String key, final int seconds) {
    	return this.d_expire(key, seconds).getResult();
    }
    
    @Override
    public OperationResult<Long> d_expire(final String key, final int seconds) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	 return DynoDualWriterClient.super.d_expire(key, seconds);
            }
        });

        return targetClient.d_expire(key, seconds);
    }
    
    @Override
    public Long expireAt(final String key, final long unixTime) {
    	return this.d_expireAt(key, unixTime).getResult();
    }
    
    
    @Override
    public OperationResult<Long> d_expireAt(final String key, final long unixTime) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_expireAt(key, unixTime);
            }
        });

        return targetClient.d_expireAt(key, unixTime);
    }
    
    @Override
    public String getSet(final String key, final String value) {
    	return this.d_getSet(key, value).getResult();
    }
    
    @Override
    public OperationResult<String> d_getSet(final String key, final String value) {
        writeAsync(key, new Callable<OperationResult<String>>(){
            @Override
            public OperationResult<String> call() throws Exception {
            	return DynoDualWriterClient.super.d_getSet(key, value);
            }
        });

        return targetClient.d_getSet(key, value);
    }
    
    @Override
    public Long hdel(final String key, final String... fields) {
    	return this.d_hdel(key, fields).getResult();
    }
    
    @Override
    public OperationResult<Long> d_hdel(final String key, final String... fields) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_hdel(key, fields);
            }
        });

        return targetClient.d_hdel(key);
    }
    
    @Override
    public Long hincrBy(final String key, final String field, final long value) {
    	return this.d_hincrBy(key, field, value).getResult();
    }
    
    @Override
    public OperationResult<Long> d_hincrBy(final String key, final String field, final long value) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_hincrBy(key, field, value);
            }
        });

        return targetClient.d_hincrBy(key, field, value);
    }
    
    public Double hincrByFloat(final String key, final String field, final double value) {
        return this.d_hincrByFloat(key, field, value).getResult();
    }
    
    @Override
    public OperationResult<Double> d_hincrByFloat(final String key, final String field, final double value)  {
        writeAsync(key, new Callable<OperationResult<Double>>(){
            @Override
            public OperationResult<Double> call() throws Exception {
            	return DynoDualWriterClient.super.d_hincrByFloat(key, field, value);
            }
        });

        return targetClient.d_hincrByFloat(key, field, value);
    }
    
    @Override
    public Long hsetnx(final String key, final String field, final String value) {
        return this.d_hsetnx(key, field, value).getResult();
    }
    
    @Override
    public OperationResult<Long> d_hsetnx(final String key, final String field, final String value) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_hsetnx(key, field, value);
            }
        });

        return targetClient.d_hsetnx(key, field, value);
    }
    
    @Override
    public Long incr(final String key) {
        return this.d_incr(key).getResult();
    }
    
    @Override
    public OperationResult<Long> d_incr(final String key) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_incr(key);
            }
        });

        return targetClient.d_incr(key);
    }
    
    @Override
    public Long incrBy(final String key, final long delta) {
        return this.d_incrBy(key, delta).getResult();
    }
    
    @Override
    public OperationResult<Long> d_incrBy(final String key, final Long delta) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_incrBy(key, delta);
            }
        });

        return targetClient.d_incrBy(key, delta);
    }
    
    public Double incrByFloat(final String key, final double increment) {
        return this.d_incrByFloat(key, increment).getResult();
    }
    
    @Override
    public OperationResult<Double> d_incrByFloat(final String key, final Double increment) {
        writeAsync(key, new Callable<OperationResult<Double>>(){
            @Override
            public OperationResult<Double> call() throws Exception {
            	return DynoDualWriterClient.super.d_incrByFloat(key, increment);
            }
        });

        return targetClient.d_incrByFloat(key, increment);
    }
    
    @Override
    public String lpop(final String key) {
        return this.d_lpop(key).getResult();
    }
    
    @Override
    public OperationResult<String> d_lpop(final String key) {
        writeAsync(key, new Callable<OperationResult<String>>(){
            @Override
            public OperationResult<String> call() throws Exception {
            	return DynoDualWriterClient.super.d_lpop(key);
            }
        });

        return targetClient.d_lpop(key);
    }
    
    @Override
    public Long lpush(final String key, final String... values) {
        return this.d_lpush(key, values).getResult();
    }
    
    @Override
    public OperationResult<Long> d_lpush(final String key, final String... values) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_lpush(key, values);
            }
        });

        return targetClient.d_lpush(key, values);
    }
    
    @Override
    public Long lrem(final String key, final long count, final String value) {
        return d_lrem(key, count, value).getResult();
    }
     
    @Override
    public OperationResult<Long> d_lrem(final String key, final Long count, final String value) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_lrem(key, count, value);
            }
        });

        return targetClient.d_lrem(key, count, value);
    }
    
    
    @Override
    public String lset(final String key, final long index, final String value) {
        return this.d_lset(key, index, value).getResult();
    }
    
    @Override
    public OperationResult<String> d_lset(final String key, final Long index, final String value) {
        writeAsync(key, new Callable<OperationResult<String>>(){
            @Override
            public OperationResult<String> call() throws Exception {
            	return DynoDualWriterClient.super.d_lset(key, index, value);
            }
        });

        return targetClient.d_lset(key, index, value);
    }
    
    @Override
    public String ltrim(final String key, final long start, final long end) {
        return this.d_ltrim(key, start, end).getResult();
    }
    
    @Override
    public OperationResult<String> d_ltrim(final String key, final long start, final long end) {
        writeAsync(key, new Callable<OperationResult<String>>(){
            @Override
            public OperationResult<String> call() throws Exception {
            	return DynoDualWriterClient.super.d_ltrim(key, start, end);
            }
        });

        return targetClient.d_ltrim(key, start, end);
    }
    
    @Override
    public Long persist(final String key) {
        return this.d_persist(key).getResult();
    }
    
    @Override
    public OperationResult<Long> d_persist(final String key) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_persist(key);
            }
        });

        return targetClient.d_persist(key);
    }
    
    public Long pexpireAt(final String key, final long millisecondsTimestamp) {
        return this.d_pexpireAt(key, millisecondsTimestamp).getResult();
    }
    
    @Override
    public OperationResult<Long> d_pexpireAt(final String key, final Long millisecondsTimestamp) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_pexpireAt(key, millisecondsTimestamp);
            }
        });

        return targetClient.d_pexpireAt(key, millisecondsTimestamp);
    }
   
    
    public Long pttl(final String key) {
        return this.d_pttl(key).getResult();
    }
    
    @Override
    public OperationResult<Long> d_pttl(final String key) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_pttl(key);
            }
        });

        return targetClient.d_pttl(key);
    }
    
    @Override
    public String rename(String oldkey, String newkey) {
        return this.d_rename(oldkey, newkey).getResult();
    }
    
    @Override
    public OperationResult<String> d_rename(final String oldkey, final String newkey) {
        writeAsync(oldkey, new Callable<OperationResult<String>>(){
            @Override
            public OperationResult<String> call() throws Exception {
            	return DynoDualWriterClient.super.d_rename(oldkey, oldkey);
            }
        });

        return targetClient.d_rename(oldkey, oldkey);
    }
    

    public String rpop(final String key) {
        return this.d_rpop(key).getResult();
    }
    
    @Override
    public OperationResult<String> d_rpop(final String key) {
        writeAsync(key, new Callable<OperationResult<String>>(){
            @Override
            public OperationResult<String> call() throws Exception {
            	return DynoDualWriterClient.super.d_rpop(key);
            }
        });

        return targetClient.d_rpop(key);
    }
    
    @Override
    public Long scard(final String key) {
        return this.d_scard(key).getResult();
    }
    
    @Override
    public OperationResult<Long> d_scard(final String key) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_scard(key);
            }
        });

        return targetClient.d_scard(key);
    }
    
    @Override
    public Boolean setbit(final String key, final long offset, final boolean value) {
        return this.d_setbit(key, offset, value).getResult();
    }
    
    @Override
    public OperationResult<Boolean> d_setbit(final String key, final Long offset, final Boolean value) {
        writeAsync(key, new Callable<OperationResult<Boolean>>(){
            @Override
            public OperationResult<Boolean> call() throws Exception {
            	return DynoDualWriterClient.super.d_setbit(key, offset, value);
            }
        });

        return targetClient.d_setbit(key, offset, value);
    }
    
    @Override
    public Boolean setbit(final String key, final long offset, final String value) {
        return this.d_setbit(key, offset, value).getResult();
    }
    
    @Override
    public OperationResult<Boolean> d_setbit(final String key, final Long offset, final String value) {
        writeAsync(key, new Callable<OperationResult<Boolean>>(){
            @Override
            public OperationResult<Boolean> call() throws Exception {
            	return DynoDualWriterClient.super.d_setbit(key, offset, value);
            }
        });

        return targetClient.d_setbit(key, offset, value);
    }
    
    @Override
    public Long setnx(final String key, final String value) {
        return this.d_setnx(key, value).getResult();
    }
    
    @Override
    public OperationResult<Long> d_setnx(final String key, final String value) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_setnx(key, value);
            }
        });

        return targetClient.d_setnx(key, value);
    }
    
    @Override
    public Long setrange(final String key, final long offset, final String value) {
        return this.d_setrange(key, offset, value).getResult();
    }
    
    @Override
    public OperationResult<Long> d_setrange(final String key, final Long offset, final String value) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_setrange(key, offset, value);
            }
        });

        return targetClient.d_setrange(key, offset, value);
    }
    
    @Override
    public Set<String> smembers(final String key) {
        return this.d_smembers(key).getResult();
    }
    
    @Override
    public OperationResult<Set<String>> d_smembers(final String key) {
        writeAsync(key, new Callable<OperationResult<Set<String>>>(){
            @Override
            public OperationResult<Set<String>> call() throws Exception {
            	return DynoDualWriterClient.super.d_smembers(key);
            }
        });

        return targetClient.d_smembers(key);
    }
    
    public Long smove(final String srckey, final String dstkey, final String member) {
        return this.d_smove(srckey, dstkey, member).getResult();
    }
    
    @Override
    public OperationResult<Long> d_smove(final String srckey, final String dstkey, final String member) {
        writeAsync(srckey, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_smove(srckey,dstkey,member);
            }
        });

        return targetClient.d_smove(srckey,dstkey,member);
    }
    
    @Override
    public List<String> sort(String key) {
        return this.d_sort(key).getResult();
    }
    
    @Override
    public OperationResult<List<String>> d_sort(final String key) {
        writeAsync(key, new Callable<OperationResult<List<String>>>(){
            @Override
            public OperationResult<List<String>> call() throws Exception {
            	return DynoDualWriterClient.super.d_sort(key);
            }
        });

        return targetClient.d_sort(key);
    }    
    
    @Override
    public String spop(final String key) {
        return this.d_spop(key).getResult();
    }
    
    @Override
    public OperationResult<String> d_spop(final String key) {
        writeAsync(key, new Callable<OperationResult<String>>(){
            @Override
            public OperationResult<String> call() throws Exception {
            	return DynoDualWriterClient.super.d_spop(key);
            }
        });

        return targetClient.d_spop(key);
    }
    
    @Override
    public Long srem(final String key, final String... members) {
        return this.d_srem(key, members).getResult();
    }
    
    @Override
    public OperationResult<Long> d_srem(final String key, final String... members) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_srem(key,members);
            }
        });

        return targetClient.d_srem(key,members);
    }
    
    @Override
    public ScanResult<String> sscan(final String key, final String cursor) {
        return this.d_sscan(key, cursor).getResult();
    }
    
    @Override
    public OperationResult<ScanResult<String>> d_sscan(final String key, final String cursor) {
        writeAsync(key, new Callable<OperationResult<ScanResult<String>>>(){
            @Override
            public OperationResult<ScanResult<String>> call() throws Exception {
            	return DynoDualWriterClient.super.d_sscan(key,cursor);
            }
        });

        return targetClient.d_sscan(key,cursor);
    }
    
	@Override
	public ScanResult<String> sscan(final String key, final String cursor, final ScanParams params) {
        return this.d_sscan(key, cursor, params).getResult();
	}
    
    @Override
    public OperationResult<ScanResult<String>> d_sscan(final String key, final String cursor, final ScanParams params) {
        writeAsync(key, new Callable<OperationResult<ScanResult<String>>>(){
            @Override
            public OperationResult<ScanResult<String>> call() throws Exception {
            	return DynoDualWriterClient.super.d_sscan(key,cursor,params);
            }
        });

        return targetClient.d_sscan(key,cursor,params);
    }
       
    @Override
    public Long ttl(final String key) {
        return this.d_ttl(key).getResult();
    }
    
    @Override
    public OperationResult<Long> d_ttl(final String key) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_ttl(key);
            }
        });

        return targetClient.d_ttl(key);
    }

    @Override
    public Long zadd(String key, double score, String member) {
        return this.d_zadd(key, score, member).getResult();
    }

    
    @Override
    public OperationResult<Long> d_zadd(final String key, final Double score, final String member) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_zadd(key, score, member);
            }
        });

        return targetClient.d_zadd(key, score, member);
    }
    
    @Override
    public Long zadd(String key, Map<String, Double> scoreMembers) {
        return this.d_zadd(key, scoreMembers).getResult();
    }
    
    @Override
    public OperationResult<Long> d_zadd(final String key, final Map<String, Double> scoreMembers) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_zadd(key, scoreMembers);
            }
        });

        return targetClient.d_zadd(key, scoreMembers);
    }
    
    @Override
    public Double zincrby(final String key, final double score, final String member) {
        return this.d_zincrby(key, score, member).getResult();
    }
    
    @Override
    public OperationResult<Double> d_zincrby(final String key, final Double score, final String member) {
        writeAsync(key, new Callable<OperationResult<Double>>(){
            @Override
            public OperationResult<Double> call() throws Exception {
            	return DynoDualWriterClient.super.d_zincrby(key, score, member);
            }
        });

        return targetClient.d_zincrby(key, score, member);
    }
    
    @Override
    public Long zrem(String key, String... member) {
        return this.d_zrem(key, member).getResult();
    }
    
    @Override
    public OperationResult<Long> d_zrem(final String key, final String... member) {
        writeAsync(key, new Callable<OperationResult<Long>>(){
            @Override
            public OperationResult<Long> call() throws Exception {
            	return DynoDualWriterClient.super.d_zrem(key, member);
            }
        });

        return targetClient.d_zrem(key, member);
    }
    
    @Override
    public List<String> blpop(int timeout, String key) {
        return this.d_blpop(timeout,key).getResult();
    }
    
    @Override
    public OperationResult<List<String>> d_blpop(final int timeout, final String key) {
        writeAsync(key, new Callable<OperationResult<List<String>>>(){
            @Override
            public OperationResult<List<String>> call() throws Exception {
            	return DynoDualWriterClient.super.d_blpop(timeout, key);
            }
        });

        return targetClient.d_blpop(timeout, key);
    }
    
    @Override
    public List<String> brpop(int timeout, String key) {
        return this.d_brpop(timeout,key).getResult();
    }
    
    @Override
    public OperationResult<List<String>> d_brpop(final int timeout, final String key) {
        writeAsync(key, new Callable<OperationResult<List<String>>>(){
            @Override
            public OperationResult<List<String>> call() throws Exception {
            	return DynoDualWriterClient.super.d_brpop(timeout, key);
            }
        });

        return targetClient.d_brpop(timeout, key);
    }
    
    /******************* Jedis Dual write for binary commands **************/
    @Override
    public String set(final byte[] key, final byte[] value) {
        return this.d_set(key, value).getResult();
    }

    @Override
    public OperationResult<String> d_set(final byte[] key, final byte[] value) {
        writeAsync(key, new Callable<OperationResult<String>>(){
            @Override
            public OperationResult<String> call() throws Exception {
        	    return DynoDualWriterClient.super.d_set(key, value);
           }
        });

        return targetClient.d_set(key, value);
     }
    
    @Override
    public String setex(final byte[] key, final int seconds, final byte[] value) {
        return this.d_setex(key, seconds, value).getResult();
    }

    @Override
    public OperationResult<String> d_setex(final byte[] key, final Integer seconds, final byte[] value) {
        writeAsync(key, new Callable<OperationResult<String>>(){
            @Override
            public OperationResult<String> call() throws Exception {
        	    return DynoDualWriterClient.super.d_setex(key, seconds, value);
           }
        });

        return targetClient.d_setex(key, seconds, value);
     }
    
}
