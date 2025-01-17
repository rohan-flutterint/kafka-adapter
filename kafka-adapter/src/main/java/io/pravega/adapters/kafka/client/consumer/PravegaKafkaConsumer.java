/**
 * Copyright (c) Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.adapters.kafka.client.consumer;

import com.google.common.annotations.VisibleForTesting;
import io.pravega.adapters.kafka.client.shared.PravegaConfig;
import io.pravega.adapters.kafka.client.shared.PravegaConsumerConfig;
import io.pravega.adapters.kafka.client.shared.PravegaReader;
import io.pravega.adapters.kafka.client.shared.Reader;
import io.pravega.client.stream.EventRead;
import io.pravega.client.stream.ReinitializationRequiredException;
import io.pravega.client.stream.Serializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.time.StopWatch;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.Deserializer;

import static org.apache.kafka.clients.consumer.ConsumerRecord.NO_TIMESTAMP;
import static org.apache.kafka.clients.consumer.ConsumerRecord.NULL_CHECKSUM;
import static org.apache.kafka.clients.consumer.ConsumerRecord.NULL_SIZE;

@Slf4j
public class PravegaKafkaConsumer<K, V> implements Consumer<K, V> {

    private static final int DEFAULT_READ_TIMEOUT_IN_MILLIS = 500;

    private static final int DEFAULT_RECORDS_TO_READ_PER_READER_AND_ITERATION = 10;

    private final List<ConsumerInterceptor<K, V>> interceptors;

    private final String controllerUri;

    private final String scope;

    private final String readerGroupId;

    private final String readerId;

    private final int readTimeout;

    private final int maxPollRecords;

    @VisibleForTesting
    @Getter(AccessLevel.PACKAGE)
    @Setter(AccessLevel.PACKAGE)
    private Map<String, Reader<V>> readersByStream = new HashMap<>();

    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    private final Serializer deserializer;

    public PravegaKafkaConsumer(final Properties kafkaConfigProperties) {
        this(kafkaConfigProperties, null, null);
    }

    public PravegaKafkaConsumer(final Properties configProperties,
                                Deserializer<K> keyDeserializer, Deserializer<V> valueDeserializer) {
        if (keyDeserializer != null) {
            configProperties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                    keyDeserializer.getClass().getCanonicalName());
        }

        if (valueDeserializer != null) {
            configProperties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                    valueDeserializer.getClass().getCanonicalName());
        }

        PravegaConsumerConfig config = new PravegaConsumerConfig(configProperties);
        controllerUri = config.getServerEndpoints();
        scope = config.getScope() != null ? config.getScope() : PravegaConfig.DEFAULT_SCOPE;
        deserializer = config.getSerializer();
        readerGroupId = config.getGroupId(UUID.randomUUID().toString());
        readerId = config.getClientId("default_readerId");
        interceptors = (List) (new ConsumerConfig(configProperties)).getConfiguredInstances(
                ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG, ConsumerInterceptor.class);
        readTimeout = config.getReadTimeoutInMs();
        maxPollRecords = config.getMaxPollRecords();
    }


    /**
     * In Pravega manual assignment of segments is not applicable, as segments (or partitions) are not fixed and
     * can scale dynamically.
     *
     * @throws UnsupportedOperationException If invoked
     */
    @Override
    public Set<TopicPartition> assignment() {
        log.debug("assignment() hit");
        return new HashSet<TopicPartition>(); // For Flink Kafka connector
        //throw new UnsupportedOperationException(
        // "Manually assigning list of partitions to this serialization is not supported");
    }

    /**
     * Fetches the topics/segments that the serialization is subscribed to.
     *
     * @return The set of segments that this serialization is subscribed to
     */
    @Override
    public Set<String> subscription() {
        log.debug("Returning subscriptions");
        return this.readersByStream.keySet();
    }

    @Override
    public void subscribe(Collection<String> topics) {
        subscribe(topics, null);
    }


    @Override
    public void subscribe(@NonNull Collection<String> topics, ConsumerRebalanceListener callback) {
        log.debug("Subscribing to topics: {}, with callback {}", topics, callback);
        ensureNotClosed();

        closeAllReaders();
        readersByStream = new HashMap<>();

        int i = 0;
        for (String topic : topics) {
            i++;
            if (!readersByStream.containsKey(topic)) {
                String readerGroupName = this.readerGroupId;
                if (topics.size() > 1) {
                    readerGroupName = readerGroupName + "-" + i;
                }
                // The reason we are not reusing existing readers is because in the case of multiple topics,
                // the internal topic name might change depending of the index of the topic in the list.
                PravegaReader reader = new PravegaReader(this.scope, topic, this.controllerUri, this.deserializer,
                        readerGroupName, this.readerId);
                readersByStream.put(topic, reader);
            }
        }

    }

    @Override
    public void subscribe(Pattern pattern, ConsumerRebalanceListener callback) {
        log.debug("Subscribe with pattern {} and callback called", pattern);
        throw new UnsupportedOperationException("Subscribing to topic(s) matching specified pattern is not supported");
    }

    @Override
    public void subscribe(Pattern pattern) {
        subscribe(pattern, null);
    }

    @Override
    public void unsubscribe() {
        ensureNotClosed();
        log.debug("Un-subscribing from all topics");
        closeAllReaders();
        readersByStream = new HashMap<>();
    }

    private void closeAllReaders() {
        readersByStream.forEach((k, v) -> {
            try {
                v.close();
            } catch (Exception e) {
                log.warn("Unable to close the connection: {}", e.getMessage());
            }
        });
    }

    @Override
    public void assign(Collection<TopicPartition> partitions) {
        log.debug("assign called with partitions: {}", partitions);

        final Collection<String> topics = new ArrayList<>();
        partitions.stream().forEach(tp -> topics.add(tp.topic()));

        log.debug("invoking subscribe for topics {}", topics);
        this.subscribe(topics);
        // Flink Kafka connector uses it.
        // throw new UnsupportedOperationException("Assigning partitions not supported");
    }

    @Override
    public ConsumerRecords<K, V> poll(Duration timeout) {
        return poll(timeout.toMillis());
    }

    /**
     * Returns a map of records by topic/stream.
     *
     * @param timeout
     * @return
     */
    @Override
    public ConsumerRecords<K, V> poll(long timeout) {
        ensureNotClosed();
        if (timeout <= -1) {
            throw new IllegalArgumentException("Specified timeout is a negative value");
        }

        if (!isSubscribedToAnyTopic()) {
            throw new IllegalStateException("This consumer is not subscribed to any topics/Pravega streams");
        }

        // Here's are the key salient points on implementation:
        // - On each poll, serialization should return the records (representing) since last read position in the
        // segments.
        // In Kafka, the last read position/offset is set either manually (through a seek() call) or automatically
        // based on a auto commit configuration. In Pravega too it can be set manually or automatically (by default).
        // For now, we'll not set the offsets manually.
        //
        // - Timeouts:
        //      - If 0, return immediately with whatever records that are in the buffer.
        //      - Throw exception if negative.
        //
        // - Exceptions
        //      - WakeupException - if wakeup() is called before or during invocation
        //      - InterruptException - if the calling thread is interrupted is called before or during invocation
        //      - AuthenticationException - If authentication fails.
        //      - AuthorizationException - if caller doesnot have access to the stream segments
        //
        //

        // Note: Here, we are assuming a timeout of DEFAULT_READ_TIMEOUT_IN_MILLIS (=500 ms) if timeout = 0. In
        // KafkaConsumer, on the other hand, all the preexisting records in the buffer are immediately returned
        // without any delay.
        ConsumerRecords<K, V> consumerRecords = read(timeout > 0 ? timeout : DEFAULT_READ_TIMEOUT_IN_MILLIS,
                DEFAULT_RECORDS_TO_READ_PER_READER_AND_ITERATION);
        return invokeInterceptors(this.interceptors, consumerRecords);
    }

    private boolean isSubscribedToAnyTopic() {
        return this.readersByStream.size() > 0;
    }


    @VisibleForTesting
    ConsumerRecords<K, V> read(long timeout, int numRecordsPerReaderInEachIteration) {
        log.debug("read invoked with timeout: {} and numRecordsPerReaderInEachIteration: {}", timeout,
                numRecordsPerReaderInEachIteration);
        long startTimeInMillis = System.currentTimeMillis();
        AtomicInteger totalCountOfRecords = new AtomicInteger(0);

        assert timeout > 0;
        assert numRecordsPerReaderInEachIteration > 0;
        ensureNotClosed();

        // We use this to honor the timeout, on a best effort basis. The timeout out not strict - there will be cases
        // where result is returned in a duration that is slightly later than the specified timeout.
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        final Map<TopicPartition, List<ConsumerRecord<K, V>>> recordsByPartition = new HashMap<>();
        log.debug("Size of readersByStream={}", this.readersByStream.size());

        // Check that we haven't crossed the timeout yet before starting the iteration again
        while (stopWatch.getTime() < timeout) {
            long finalTimeout = timeout;

            this.readersByStream.entrySet().stream().forEach(i -> {
                ensureNotClosed();

                // Check that we haven't crossed the timeout yet before initiating reads from the next reader.
                if (stopWatch.getTime() < finalTimeout) {
                    String stream = i.getKey();
                    log.debug("Reading data for scope/stream [{}/{}]", scope, i.getKey());

                    TopicPartition topicPartition = new TopicPartition(stream, -1);
                    Reader<V> reader = i.getValue();

                    List<ConsumerRecord<K, V>> recordsToAdd = new ArrayList<>();
                    EventRead<V> event = null;

                    int countOfReadEvents = 0;
                    do {
                        try {
                            event = reader.readNextEvent(readTimeout);
                            if (event.getEvent() != null) {
                                log.trace("Found a non-null event");
                                recordsToAdd.add(translateToConsumerRecord(stream, event));
                                countOfReadEvents++;
                                totalCountOfRecords.addAndGet(1);
                            }
                        } catch (ReinitializationRequiredException e) {
                            throw e;
                        }
                    } while (event.getEvent() != null
                            && countOfReadEvents <= numRecordsPerReaderInEachIteration
                            && totalCountOfRecords.get() <= this.maxPollRecords
                            && stopWatch.getTime() < finalTimeout);

                    if (!recordsToAdd.isEmpty()) {
                        log.debug("{} records to add", recordsToAdd.size());
                        if (recordsByPartition.containsKey(topicPartition)) {
                            recordsByPartition.get(topicPartition).addAll(recordsToAdd);
                        } else {
                            recordsByPartition.put(topicPartition, recordsToAdd);
                        }
                    } else {
                        log.debug("No records to add");
                    }
                } else {
                    log.debug("Read time already expired for stream: {}", i.getKey());
                }
            });
        }
        log.debug("Returning {} records in {} ms. against a timeout of {} ms.",
                totalCountOfRecords.get(), System.currentTimeMillis() - startTimeInMillis, timeout);
        return new ConsumerRecords<K, V>(recordsByPartition);
    }

    private ConsumerRecord<K, V> translateToConsumerRecord(String stream, EventRead<V> event) {
        int partition = 0;

        // Refers to the offset that points to the record in a partition
        int offset = 0;

        return new ConsumerRecord(stream, partition, offset, NO_TIMESTAMP, TimestampType.NO_TIMESTAMP_TYPE,
                NULL_CHECKSUM, NULL_SIZE, NULL_SIZE, null, event.getEvent());

    }

    private ConsumerRecords invokeInterceptors(List<ConsumerInterceptor<K, V>> interceptors,
                                               ConsumerRecords consumerRecords) {
        ConsumerRecords processedRecords = consumerRecords;
        for (ConsumerInterceptor interceptor : interceptors) {
            try {
                processedRecords = interceptor.onConsume(processedRecords);
            } catch (Exception e) {
                log.warn("Encountered exception executing interceptor {}.", interceptor.getClass().getCanonicalName(),
                        e);
                // ignore
            }
        }
        return processedRecords;
    }

    @Override
    public void commitSync() {
        // Pravega always "commits", nothing special to do.
    }

    @Override
    public void commitSync(Duration timeout) {
        // Pravega always "commits", nothing special to do.
    }

    @Override
    public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
        // Pravega always "commits", nothing special to do.
    }

    @Override
    public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets, Duration timeout) {
        // Pravega always "commits", nothing special to do.
    }

    @Override
    public void commitAsync() {
        // Pravega always "commits", nothing special to do.
    }

    @Override
    public void commitAsync(OffsetCommitCallback callback) {
        // Pravega always "commits", nothing special to do.
    }

    @Override
    public void commitAsync(Map<TopicPartition, OffsetAndMetadata> offsets, OffsetCommitCallback callback) {
        // Pravega always "commits", nothing special to do.
    }

    @Override
    public void seek(TopicPartition partition, long offset) {
        throw new UnsupportedOperationException("Seek is not supported");
    }

    @Override
    public void seek(TopicPartition partition, OffsetAndMetadata offsetAndMetadata) {
        throw new UnsupportedOperationException("Seek is not supported");
    }

    @Override
    public void seekToBeginning(Collection<TopicPartition> partitions) {
        throw new UnsupportedOperationException("Seek is not supported");
    }

    @Override
    public void seekToEnd(Collection<TopicPartition> partitions) {
        throw new UnsupportedOperationException("Seek is not supported");
    }

    @Override
    public long position(TopicPartition partition) {
        return -1;
    }

    @Override
    public long position(TopicPartition partition, Duration timeout) {
        return -1;
    }

    @Override
    public OffsetAndMetadata committed(TopicPartition partition) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public OffsetAndMetadata committed(TopicPartition partition, Duration timeout) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public Map<TopicPartition, OffsetAndMetadata> committed(Set<TopicPartition> partitions) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public Map<TopicPartition, OffsetAndMetadata> committed(Set<TopicPartition> partitions, Duration timeout) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public Map<MetricName, ? extends Metric> metrics() {
        // throw new UnsupportedOperationException("Not supported");
        return new HashMap<>();
    }

    @Override
    public List<PartitionInfo> partitionsFor(String topic) {
        return partitionsFor(topic, Duration.ofMillis(200));
    }

    @Override
    public List<PartitionInfo> partitionsFor(String topic, Duration timeout) {
        // throw new UnsupportedOperationException("Not supported");

        // This method is internally invoked by Flink Kafka adapter.
        PartitionInfo info = new PartitionInfo(topic, 0, null, null, null);
        List<PartitionInfo> result = new ArrayList<>();
        result.add(info);
        return result;
    }

    @Override
    public Map<String, List<PartitionInfo>> listTopics() {
        log.debug("listTopics invoked");
        final Map<String, List<PartitionInfo>> result = new HashMap<>();
        this.readersByStream.keySet().stream().forEach(topic ->
                result.put(topic, Arrays.asList(
                        new PartitionInfo(topic, 0, null, null, null))));
        return result;
        //throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public Map<String, List<PartitionInfo>> listTopics(Duration timeout) {
        return listTopics();
    }

    @Override
    public Set<TopicPartition> paused() {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void pause(Collection<TopicPartition> partitions) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void resume(Collection<TopicPartition> partitions) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes(Map<TopicPartition, Long> timestampsToSearch) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes(Map<TopicPartition, Long> timestampsToSearch,
                                                                   Duration timeout) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions, Duration timeout) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions, Duration timeout) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void close() {
        close(Duration.ofMillis(Long.MAX_VALUE));
    }

    @Override
    public void close(long timeout, TimeUnit unit) {
        close(Duration.ofMillis(unit.toMillis(timeout)));
    }

    @Override
    public void close(Duration timeout) {
        cleanup();
    }

    private void cleanup() {
        log.debug("Closing the consumer");
        if (!isClosed.get()) {
            closeAllReaders();
            isClosed.set(true);
        }
    }

    @Override
    public void wakeup() {
        // throw new UnsupportedOperationException("Not supported");
        // Ignore. This method is invoked by Flink Kafka connector.
    }

    private void ensureNotClosed() {
        if (isClosed.get()) {
            throw new IllegalStateException("This instance is closed already");
        }
    }
}

