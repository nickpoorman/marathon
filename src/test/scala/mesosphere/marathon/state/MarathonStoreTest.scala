package mesosphere.marathon.state

import java.lang.{ Boolean => JBoolean }
import java.util
import java.util.concurrent.{ ExecutionException, Future => JFuture }

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.{ MarathonConf, MarathonSpec, StorageException }
import org.apache.mesos.state.{ InMemoryState, State, Variable }
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.rogach.scallop.ScallopConf
import org.scalatest.Matchers

import scala.collection.immutable.Seq
import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps

class MarathonStoreTest extends MarathonSpec with Matchers {
  test("Fetch") {
    val state = mock[State]
    val future = mock[JFuture[Variable]]
    val variable = mock[Variable]
    val appDef = AppDefinition(id = "testApp".toPath, args = Some(Seq("arg")))
    val registry = new MetricRegistry
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    when(variable.value()).thenReturn(appDef.toProtoByteArray)
    when(future.get(anyLong, any[TimeUnit])).thenReturn(variable)
    when(state.fetch("app:testApp")).thenReturn(future)
    when(state.fetch("__internal__:app:storage:version")).thenReturn(currentVersionFuture)
    when(state.store(currentVersionVariable)).thenReturn(currentVersionFuture)
    val store = new MarathonStore[AppDefinition](config, state, registry, () => AppDefinition())
    val res = store.fetch("testApp")

    verify(state).fetch("app:testApp")
    assert(Some(appDef) == Await.result(res, 5.seconds), "Should return the expected AppDef")
  }

  test("FetchFail") {
    val state = mock[State]
    val future = mock[JFuture[Variable]]
    val registry = new MetricRegistry

    when(future.get(anyLong, any[TimeUnit])).thenReturn(null)
    when(state.fetch("app:testApp")).thenReturn(future)
    when(state.fetch("__internal__:app:storage:version")).thenReturn(currentVersionFuture)
    when(state.store(currentVersionVariable)).thenReturn(currentVersionFuture)

    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()
    val store = new MarathonStore[AppDefinition](config, state, registry, () => AppDefinition())
    val res = store.fetch("testApp")

    verify(state).fetch("app:testApp")

    intercept[StorageException] {
      Await.result(res, 5.seconds)
    }
  }

  test("Modify") {
    val state = mock[State]
    val future = mock[JFuture[Variable]]
    val variable = mock[Variable]
    val appDef = AppDefinition(id = "testApp".toPath, args = Some(Seq("arg")))
    val registry = new MetricRegistry

    val newAppDef = appDef.copy(id = "newTestApp".toPath)
    val newVariable = mock[Variable]
    val newFuture = mock[JFuture[Variable]]
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    when(newVariable.value()).thenReturn(newAppDef.toProtoByteArray)
    when(newFuture.get(anyLong, any[TimeUnit])).thenReturn(newVariable)
    when(variable.value()).thenReturn(appDef.toProtoByteArray)
    when(variable.mutate(any())).thenReturn(newVariable)
    when(future.get(anyLong, any[TimeUnit])).thenReturn(variable)
    when(state.fetch("app:testApp")).thenReturn(future)
    when(state.store(newVariable)).thenReturn(newFuture)
    when(state.fetch("__internal__:app:storage:version")).thenReturn(currentVersionFuture)
    when(state.store(currentVersionVariable)).thenReturn(currentVersionFuture)

    val store = new MarathonStore[AppDefinition](config, state, registry, () => AppDefinition())
    val res = store.modify("testApp") { _ =>
      newAppDef
    }

    assert(Some(newAppDef) == Await.result(res, 5.seconds), "Should return the new AppDef")
    verify(state).fetch("app:testApp")
    verify(state).store(newVariable)
  }

  test("ModifyFail") {
    val state = mock[State]
    val future = mock[JFuture[Variable]]
    val variable = mock[Variable]
    val appDef = AppDefinition(id = "testApp".toPath, args = Some(Seq("arg")))
    val registry = new MetricRegistry

    val newAppDef = appDef.copy(id = "newTestApp".toPath)
    val newVariable = mock[Variable]
    val newFuture = mock[JFuture[Variable]]
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    when(newVariable.value()).thenReturn(newAppDef.toProtoByteArray)
    when(newFuture.get(anyLong, any[TimeUnit])).thenReturn(null)
    when(variable.value()).thenReturn(appDef.toProtoByteArray)
    when(variable.mutate(any())).thenReturn(newVariable)
    when(future.get(anyLong, any[TimeUnit])).thenReturn(variable)
    when(state.fetch("app:testApp")).thenReturn(future)
    when(state.store(newVariable)).thenReturn(newFuture)
    when(state.fetch("__internal__:app:storage:version")).thenReturn(currentVersionFuture)
    when(state.store(currentVersionVariable)).thenReturn(currentVersionFuture)

    val store = new MarathonStore[AppDefinition](config, state, registry, () => AppDefinition())
    val res = store.modify("testApp") { _ =>
      newAppDef
    }

    intercept[StorageException] {
      Await.result(res, 5.seconds)
    }
  }

  test("Expunge") {
    val state = mock[State]
    val future = mock[JFuture[Variable]]
    val variable = mock[Variable]
    val resultFuture = mock[JFuture[JBoolean]]
    val registry = new MetricRegistry
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    when(future.get(anyLong, any[TimeUnit])).thenReturn(variable)
    when(state.fetch("app:testApp")).thenReturn(future)
    when(resultFuture.get(anyLong, any[TimeUnit])).thenReturn(true)
    when(state.expunge(variable)).thenReturn(resultFuture)
    when(state.fetch("__internal__:app:storage:version")).thenReturn(currentVersionFuture)
    when(state.store(currentVersionVariable)).thenReturn(currentVersionFuture)

    val store = new MarathonStore[AppDefinition](config, state, registry, () => AppDefinition())

    val res = store.expunge("testApp")

    assert(Await.result(res, 5.seconds), "Expunging existing variable should return true")
    verify(state).fetch("app:testApp")
    verify(state).expunge(variable)
  }

  test("ExpungeFail") {
    val state = mock[State]
    val future = mock[JFuture[Variable]]
    val variable = mock[Variable]
    val resultFuture = mock[JFuture[JBoolean]]
    val registry = new MetricRegistry
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    when(future.get(anyLong, any[TimeUnit])).thenReturn(variable)
    when(state.fetch("app:testApp")).thenReturn(future)
    when(resultFuture.get(anyLong, any[TimeUnit])).thenReturn(null)
    when(state.expunge(variable)).thenReturn(resultFuture)
    when(state.fetch("__internal__:app:storage:version")).thenReturn(currentVersionFuture)
    when(state.store(currentVersionVariable)).thenReturn(currentVersionFuture)

    val store = new MarathonStore[AppDefinition](config, state, registry, () => AppDefinition())

    val res = store.expunge("testApp")

    intercept[StorageException] {
      Await.result(res, 5.seconds)
    }
  }

  test("Names") {
    val state = new InMemoryState
    val registry = new MetricRegistry
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    def populate(key: String, value: Array[Byte]) = {
      val variable = state.fetch(key).get().mutate(value)
      state.store(variable)
    }

    populate("app:foo", Array())
    populate("app:bar", Array())
    populate("no_match", Array())
    populate("__internal__:app:storage:version", StorageVersions.current.toByteArray)

    val store = new MarathonStore[AppDefinition](config, state, registry, () => AppDefinition())
    val res = store.names()

    assert(Set("foo", "bar") == Await.result(res, 5.seconds).toSet, "Should return all application keys")
  }

  test("NamesFail") {
    val state = mock[State]
    val registry = new MetricRegistry
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    when(state.names()).thenThrow(classOf[ExecutionException])
    when(state.fetch("__internal__:app:storage:version")).thenReturn(currentVersionFuture)
    when(state.store(currentVersionVariable)).thenReturn(currentVersionFuture)

    val store = new MarathonStore[AppDefinition](config, state, registry, () => AppDefinition())
    val res = store.names()

    assert(Await.result(res, 5.seconds).isEmpty, "Should return empty iterator")
  }

  test("ConcurrentModifications") {
    import mesosphere.util.ThreadPoolContext.context
    val state = new InMemoryState
    val registry = new MetricRegistry
    val variable = state.fetch("__internal__:app:storage:version").get().mutate(StorageVersions.current.toByteArray)
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    state.store(variable)

    val store = new MarathonStore[AppDefinition](config, state, registry, () => AppDefinition())

    Await.ready(store.store("foo", AppDefinition(id = "foo".toPath, instances = 0)), 2.seconds)

    def plusOne() = {
      store.modify("foo") { f =>
        val appDef = f()

        appDef.copy(instances = appDef.instances + 1)
      }
    }

    val results = for (_ <- 0 until 1000) yield plusOne()
    val res = Future.sequence(results)

    Await.ready(res, 5.seconds)

    assert(1000 == Await.result(store.fetch("foo"), 5.seconds).map(_.instances)
      .getOrElse(0), "Instances of 'foo' should be set to 1000")
  }

  // regression test for #1481
  test("names() correctly uses timeouts") {
    val state = new InMemoryState() {
      override def names(): JFuture[util.Iterator[String]] = new JFuture[util.Iterator[String]] {
        override def isCancelled: Boolean = false
        override def cancel(b: Boolean): Boolean = false
        override def isDone: Boolean = false

        override def get(): util.Iterator[String] = synchronized {
          wait()
          null
        }

        override def get(l: Long, timeUnit: TimeUnit): util.Iterator[String] = synchronized {
          wait(Duration(l, timeUnit).toMillis)
          null
        }
      }
    }
    val config = new ScallopConf(Seq("--master", "foo", "--marathon_store_timeout", "10")) with MarathonConf
    config.afterInit()

    val store = new MarathonStore[AppDefinition](config, state, new MetricRegistry, () => AppDefinition())

    noException should be thrownBy {
      Await.result(store.names(), 1.second)
    }
  }

  private val currentVersionVariable = {
    val versionVariable = mock[Variable]
    when(versionVariable.value()).thenReturn(StorageVersions.current.toByteArray)
    when(versionVariable.mutate(any[Array[Byte]]())).thenReturn(versionVariable)

    versionVariable
  }

  private val currentVersionFuture = {
    val versionFuture = mock[JFuture[Variable]]
    when(versionFuture.get(anyLong(), any[TimeUnit])).thenReturn(currentVersionVariable)
    versionFuture
  }
}
