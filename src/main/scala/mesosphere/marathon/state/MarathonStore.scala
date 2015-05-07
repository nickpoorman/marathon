package mesosphere.marathon.state

import com.codahale.metrics.MetricRegistry.name
import com.codahale.metrics.{ Histogram, MetricRegistry }
import com.google.protobuf.InvalidProtocolBufferException
import mesosphere.marathon.{ MarathonConf, StorageException }
import mesosphere.util.{ BackToTheFuture, LockManager, ThreadPoolContext }
import org.apache.mesos.state.State
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionException, Future }

class MarathonStore[S <: MarathonState[_, S]](
  conf: MarathonConf,
  state: State,
  registry: MetricRegistry,
  newState: () => S,
  prefix: String = "app:")(
    implicit val timeout: BackToTheFuture.Timeout = BackToTheFuture.Timeout(Duration(conf.marathonStoreTimeout(),
      MILLISECONDS)))
    extends PersistenceStore[S] {

  import BackToTheFuture.futureToFutureOption
  import ThreadPoolContext.context

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] lazy val locks = LockManager[String]()

  def contentClassName: String = newState().getClass.getSimpleName

  protected[this] val bytesRead: Histogram = registry.histogram(name(getClass, contentClassName, "read-data-size"))
  protected[this] val bytesWritten: Histogram = registry.histogram(name(getClass, contentClassName, "write-data-size"))

  def fetch(key: String): Future[Option[S]] = {
    state.fetch(prefix + key) map {
      case Some(variable) =>
        bytesRead.update(variable.value.length)
        try {
          Some(stateFromBytes(variable.value))
        }
        catch {
          case e: InvalidProtocolBufferException =>
            if (variable.value.nonEmpty) {
              log.error(s"Failed to read $key, could not deserialize data (${variable.value.length} bytes).", e)
            }
            None
        }
      case None =>
        throw new StorageException(s"Failed to read $key, does not exist, should have been created automatically.")
    }
  }

  def modify(key: String)(f: (() => S) => S): Future[Option[S]] = {
    val lock = locks.get(key)
    lock.acquire()

    val res: Future[Option[S]] = state.fetch(prefix + key) flatMap {
      case Some(variable) =>
        bytesRead.update(variable.value.length)
        val deserialize = { () =>
          try {
            stateFromBytes(variable.value)
          }
          catch {
            case e: InvalidProtocolBufferException =>
              if (variable.value.nonEmpty) {
                log.error(s"Failed to read $key, could not deserialize data (${variable.value.length} bytes).", e)
              }
              newState()
          }
        }
        val newValue: S = f(deserialize)
        state.store(variable.mutate(newValue.toProtoByteArray)) map {
          case Some(newVar) =>
            bytesWritten.update(newVar.value.size)
            Some(stateFromBytes(newVar.value))
          case None =>
            throw new StorageException(s"Failed to store $key")
        }
      case None =>
        throw new StorageException(s"Failed to read $key, does not exist, should have been created automatically.")
    }

    res onComplete { _ =>
      lock.release()
    }

    res
  }

  def expunge(key: String): Future[Boolean] = {
    val lock = locks.get(key)
    lock.acquire()

    val res = state.fetch(prefix + key) flatMap {
      case Some(variable) =>
        bytesRead.update(Option(variable.value).map(_.length).getOrElse(0))
        state.expunge(variable) map {
          case Some(b) => b.booleanValue()
          case None    => throw new StorageException(s"Failed to expunge $key")
        }

      case None => throw new StorageException(s"Failed to read $key")
    }

    res onComplete { _ =>
      lock.release()
    }

    res
  }

  def names(): Future[Iterator[String]] = {
    // TODO use implicit conversion after it has been merged
    Future {
      try {
        state.names().get(conf.marathonStoreTimeout(), MILLISECONDS).asScala.collect {
          case name if name startsWith prefix =>
            name.replaceFirst(prefix, "")
        }
      }
      catch {
        // Thrown when node doesn't exist
        case e: ExecutionException => Seq().iterator
      }
    }
  }

  private def stateFromBytes(bytes: Array[Byte]): S = {
    newState().mergeFromProto(bytes)
  }
}
