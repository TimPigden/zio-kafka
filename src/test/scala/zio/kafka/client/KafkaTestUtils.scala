package zio.kafka.client

import net.manub.embeddedkafka.EmbeddedKafka
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.common.TopicPartition
import zio.{Chunk, Managed, RIO, ZIO}
import org.apache.kafka.clients.producer.ProducerRecord
import zio.blocking.Blocking
import zio.clock.Clock
import zio.kafka.client.serde.Serde
import zio.duration._
import zio.kafka.client.Kafka.KafkaTestEnvironment
import zio.test.environment.{Live, TestEnvironment}

trait Kafka {
  def kafka: Kafka.Service
}

object Kafka {
  trait Service {
    def bootstrapServers: List[String]
  }

  val make: Managed[Nothing, Kafka] =
    Managed.fromEffect( ZIO.effectTotal( new Kafka {
      override val kafka: Service = new Kafka.Service {
        val embeddedK = EmbeddedKafka.start()
        override def bootstrapServers: List[String] = List(s"localhost:${embeddedK.config.kafkaPort}")
      }
    }))

  type KafkaTestEnvironment = Kafka with TestEnvironment

  type KafkaClockBlocking = Kafka with Clock with Blocking

  def liveClockBlocking: ZIO[KafkaTestEnvironment, Nothing, KafkaClockBlocking] =
    for {
      clck <- Live.live(ZIO.environment[Clock])
      blcking <- ZIO.environment[Blocking]
      kfka <- ZIO.environment[Kafka]
    } yield new Kafka with Clock with Blocking {
      override def kafka: Service = kfka.kafka

      override val clock: Clock.Service[Any] = clck.clock
      override val blocking: Blocking.Service[Any] = blcking.blocking
    }

}

object KafkaTestUtils {

  val kafkaEnvironment: Managed[Nothing, KafkaTestEnvironment] =
    for {
      testEnvironment <- TestEnvironment.Value
      kafkaService    <- Kafka.make
    } yield new TestEnvironment(
      testEnvironment.blocking,
      testEnvironment.clock,
      testEnvironment.console,
      testEnvironment.live,
      testEnvironment.random,
      testEnvironment.sized,
      testEnvironment.system
    ) with Kafka {
      val kafka = kafkaService.kafka
    }


  def producerSettings =
    for {
      servers <- ZIO.access[Kafka](_.kafka.bootstrapServers)
    } yield ProducerSettings(
      servers,
      5.seconds,
      Map.empty
    )

  def withProducer[A, K, V](r: Producer[Any, K, V] => RIO[Any with Clock with Kafka with Blocking, A],
                            kSerde: Serde[Any, K],
                            vSerde: Serde[Any, V]): RIO[KafkaTestEnvironment, A] =
    for {
      settings <- producerSettings
      producer = Producer.make(settings, kSerde, vSerde)
      lcb <- Kafka.liveClockBlocking
      produced <- producer.use { p => r(p).provide(lcb)}
    } yield produced

  def withProducerStrings[A](r: Producer[Any, String, String] => RIO[Any with Clock with Kafka with Blocking, A])
    = withProducer(r, Serde.string, Serde.string)

  def produceOne(t: String, k: String, m: String) =
    withProducerStrings { p =>
        p.produce(new ProducerRecord(t, k, m))
    }

  def produceMany(t: String, kvs: List[(String, String)]) =
    withProducerStrings { p =>
      val records = kvs.map {
        case (k, v) => new ProducerRecord[String, String](t, k, v)
      }
      val chunk = Chunk.fromIterable(records)
      p.produceChunk(chunk)
    }

  def produceMany(topic: String, partition: Int, kvs: List[(String, String)]) =
    withProducerStrings { p =>
      val records = kvs.map {
        case (k, v) => new ProducerRecord[String, String](topic, partition, null, k, v)
      }
      val chunk = Chunk.fromIterable(records)
      p.produceChunk(chunk)
    }

  def consumerSettings(groupId: String, clientId: String) =
    for {
      servers <- ZIO.access[Kafka](_.kafka.bootstrapServers)
    } yield ConsumerSettings(
      servers,
      groupId,
      clientId,
      5.seconds,
      Map(
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest",
        ConsumerConfig.METADATA_MAX_AGE_CONFIG  -> "100"
      ),
      250.millis,
      250.millis,
      1
    )
  
  def withConsumer[A](groupId: String, clientId: String)
                     (r: Consumer => RIO[Any with Kafka with Clock with Blocking, A]): RIO[KafkaTestEnvironment, A] =
    for {
      lcb <- Kafka.liveClockBlocking
      inner <- (for {
        settings <- consumerSettings(groupId, clientId)
        consumer = Consumer.make(settings)
        produced <- consumer.use { p => r(p).provide(lcb) }
      } yield produced).provide(lcb)
    } yield inner

  def recordsFromAllTopics[K, V](
    pollResult: Map[TopicPartition, Chunk[ConsumerRecord[K, V]]]
  ): Chunk[ConsumerRecord[K, V]] =
    Chunk.fromIterable(pollResult.values).flatMap(identity)

  def getAllRecordsFromMultiplePolls[K, V](
    res: List[Map[TopicPartition, Chunk[ConsumerRecord[K, V]]]]
  ): Chunk[ConsumerRecord[K, V]] =
    res.foldLeft[Chunk[ConsumerRecord[K, V]]](Chunk.empty)(
      (acc, pollResult) => acc ++ recordsFromAllTopics[K, V](pollResult)
    )

  def tp(topic: String, partition: Int): TopicPartition = new TopicPartition(topic, partition)
}
