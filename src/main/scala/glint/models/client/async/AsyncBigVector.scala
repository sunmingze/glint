package glint.models.client.async

import java.io.{IOException, ObjectInputStream, ObjectOutputStream}

import akka.actor.{ActorRef, ActorSystem, ExtendedActorSystem}
import akka.pattern.ask
import akka.serialization.JavaSerializer
import akka.util.Timeout
import breeze.linalg.DenseVector
import breeze.math.Semiring
import com.typesafe.config.Config
import glint.indexing.Indexer
import glint.messages.server.request.PullVector
import glint.models.client.BigVector
import glint.partitioning.Partitioner

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

/**
  * An asynchronous implementation of the BigVector.
  *
  * @param partitioner A partitioner to map rows to parameter servers
  * @param indexer An indexer to remap rows
  * @param rows The number of rows
  * @tparam V The type of values to store
  * @tparam R The type of responses we expect to get from the parameter servers
  * @tparam P The type of push requests we should send to the parameter servers
  */
abstract class AsyncBigVector[@specialized V: Semiring : ClassTag, R: ClassTag, P: ClassTag](partitioner: Partitioner[ActorRef],
                                                                                             indexer: Indexer[Long],
                                                                                             config: Config,
                                                                                             rows: Long)
  extends BigVector[V] {

  /**
    * Extracts a value from a given response at given index
    *
    * @param response The response
    * @param index The index
    * @return The value
    */
  protected def toValue(response: R, index: Int): V

  /**
    * Creates a push message from given sequence of keys and values
    *
    * @param keys The rows
    * @param values The values
    * @return A PushMatrix message for type V
    */
  protected def toPushMessage(keys: Array[Long], values: Array[V]): P

  /**
    * Pulls a set of elements
    *
    * @param keys The indices of the keys
    * @return A future containing the values of the elements at given rows, columns
    */
  override def pull(keys: Array[Long])(implicit timeout: Timeout, ec: ExecutionContext): Future[Array[V]] = {

    // Reindex keys appropriately
    val indexedKeys = keys.map(indexer.index)

    // Send pull request of the list of keys
    val pulls = mapPartitions(indexedKeys) {
      case (partition, indices) =>
        val pullMessage = PullVector(indices.map(indexedKeys).toArray)
        (partition ? pullMessage).mapTo[R]
    }

    // Obtain key indices after partitioning so we can place the results in a correctly ordered array
    val indices = indexedKeys.zipWithIndex.groupBy {
      case (k, i) => partitioner.partition(k)
    }.map {
      case (_, arr) => arr.map(_._2)
    }

    // Define aggregator for successful responses
    def aggregateSuccess(responses: Iterable[R]): Array[V] = {
      val result = DenseVector.zeros[V](keys.length)
      responses.zip(indices).foreach {
        case (response, idx) =>
          for (i <- idx.indices) {
            result(idx(i)) = toValue(response, i)
          }
      }
      result.toArray
    }

    // Combine and aggregate futures
    Future.sequence(pulls).transform(aggregateSuccess, err => err)

  }

  /**
    * Pushes a set of values
    *
    * @param keys The indices of the keys
    * @param values The values to update
    * @return A future containing either the success or failure of the operation
    */
  override def push(keys: Array[Long], values: Array[V])(implicit timeout: Timeout, ec: ExecutionContext): Future[Boolean] = {

    // Reindex rows appropriately
    val indexedKeys = keys.map(indexer.index)

    // Send push requests
    val pushes = mapPartitions(indexedKeys) {
      case (partition, indices) =>
        (partition ? toPushMessage(indices.map(indexedKeys).toArray, indices.map(values).toArray)).mapTo[Boolean]
    }

    // Combine and aggregate futures
    Future.sequence(pushes).transform(results => true, err => err)

  }

  /**
    * Groups indices of given keys into partitions according to this models partitioner and maps each partition to a
    * type T
    *
    * @param keys The keys to partition
    * @param func The function that takes a partition and corresponding indices and creates something of type T
    * @tparam T The type to map to
    * @return An iterable over the partitioned results
    */
  private def mapPartitions[T](keys: Seq[Long])(func: (ActorRef, Seq[Int]) => T): Iterable[T] = {
    keys.indices.groupBy(i => partitioner.partition(keys(i))).map { case (a, b) => func(a, b) }
  }

  /**
    * Deserializes this instance. This starts an ActorSystem with appropriate configuration before attempting to
    * deserialize ActorRefs
    *
    * @param in The object input stream
    * @throws java.io.IOException A possible Input/Output exception
    */
  @throws(classOf[IOException])
  private def readObject(in: ObjectInputStream): Unit = {
    val config = in.readObject().asInstanceOf[Config]
    val as = ActorSystem("AsyncBigMatrix", config.getConfig("glint.client"))
    JavaSerializer.currentSystem.withValue(as.asInstanceOf[ExtendedActorSystem]) {
      in.defaultReadObject()
    }
  }

  /**
    * Serializes this instance. This first writes the config before the entire object to ensure we can deserialize with
    * an ActorSystem in place
    *
    * @param out The object output stream
    * @throws java.io.IOException A possible Input/Output exception
    */
  @throws(classOf[IOException])
  private def writeObject(out: ObjectOutputStream): Unit = {
    out.writeObject(config)
    out.defaultWriteObject()
  }

}