package no.nav.paw.arbeidssokerregisteret

import no.nav.paw.arbeidssokerregisteret.intern.v1.Hendelse
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.Callback
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue

class ProducerMock<K, V> : Producer<K, V> {
    private val queue = LinkedBlockingQueue<ProducerRecord<K, V>>(100)
    fun next(): ProducerRecord<K, V> = queue.take()

    override fun close() {}

    override fun close(timeout: Duration?) {}

    override fun initTransactions() {}

    override fun beginTransaction() {}

    override fun sendOffsetsToTransaction(
        offsets: MutableMap<TopicPartition, OffsetAndMetadata>?,
        consumerGroupId: String?
    ) {
    }

    override fun sendOffsetsToTransaction(
        offsets: MutableMap<TopicPartition, OffsetAndMetadata>?,
        groupMetadata: ConsumerGroupMetadata?
    ) {
    }

    override fun commitTransaction() {}

    override fun abortTransaction() {}

    override fun flush() {}

    override fun partitionsFor(topic: String?): MutableList<PartitionInfo> {
        TODO("Not yet implemented")
    }

    override fun metrics(): MutableMap<MetricName, out Metric> {
        return mutableMapOf()
    }

    override fun clientInstanceId(timeout: Duration?): Uuid {
        return Uuid.ZERO_UUID
    }

    override fun send(record: ProducerRecord<K, V>?, callback: Callback): Future<RecordMetadata> {
        queue.put(record)
        val metedata = RecordMetadata(
            TopicPartition(record!!.topic(), 0),
            1L, 0, Instant.now().toEpochMilli(), 3, 3
        )
        val result = CompletableFuture.completedFuture(metedata)
        callback.onCompletion(metedata, null)
        return result
    }

    override fun send(record: ProducerRecord<K, V>?): Future<RecordMetadata> {
        TODO("Not yet implemented")
    }

}
