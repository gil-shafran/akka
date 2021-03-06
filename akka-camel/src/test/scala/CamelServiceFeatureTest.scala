package se.scalablesolutions.akka.camel

import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.apache.camel.builder.RouteBuilder
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, FeatureSpec}

import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.{ActiveObject, Actor, ActorRegistry}

class CamelServiceFeatureTest extends FeatureSpec with BeforeAndAfterAll with GivenWhenThen {
  import CamelServiceFeatureTest._

  var service: CamelService = _

  override protected def beforeAll = {
    ActorRegistry.shutdownAll
    // create new CamelService instance
    service = CamelService.newInstance
    // register test consumer before starting the CamelService
    actorOf(new TestConsumer("direct:publish-test-1")).start
    // Configure a custom camel route
    CamelContextManager.init
    CamelContextManager.context.addRoutes(new TestRoute)
    // start consumer publisher, otherwise we cannot set message
    // count expectations in the next step (needed for testing only).
    service.consumerPublisher.start
    // set expectations on publish count
    val latch = service.consumerPublisher.!![CountDownLatch](SetExpectedMessageCount(1)).get
    // start the CamelService
    service.load
    // await publication of first test consumer
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
  }

  override protected def afterAll = {
    service.unload
    ActorRegistry.shutdownAll
  }

  feature("Publish registered consumer actors in the global CamelContext") {

    scenario("access registered consumer actors via Camel direct-endpoints") {

      given("two consumer actors registered before and after CamelService startup")
      val latch = service.consumerPublisher.!![CountDownLatch](SetExpectedMessageCount(1)).get
      actorOf(new TestConsumer("direct:publish-test-2")).start
      assert(latch.await(5000, TimeUnit.MILLISECONDS))

      when("requests are sent to these actors")
      val response1 = CamelContextManager.template.requestBody("direct:publish-test-1", "msg1")
      val response2 = CamelContextManager.template.requestBody("direct:publish-test-2", "msg2")

      then("both actors should have replied with expected responses")
      assert(response1 === "received msg1")
      assert(response2 === "received msg2")
    }
  }

  feature("Unpublish registered consumer actor from the global CamelContext") {

    scenario("attempt access to unregistered consumer actor via Camel direct-endpoint") {
      val endpointUri = "direct:unpublish-test-1"

      given("a consumer actor that has been stopped")
      assert(CamelContextManager.context.hasEndpoint(endpointUri) eq null)
      var latch = service.consumerPublisher.!![CountDownLatch](SetExpectedMessageCount(1)).get
      val consumer = actorOf(new TestConsumer(endpointUri)).start
      assert(latch.await(5000, TimeUnit.MILLISECONDS))
      assert(CamelContextManager.context.hasEndpoint(endpointUri) ne null)

      latch = service.consumerPublisher.!![CountDownLatch](SetExpectedMessageCount(1)).get
      consumer.stop
      assert(latch.await(5000, TimeUnit.MILLISECONDS))
      // endpoint is still there but the route has been stopped
      assert(CamelContextManager.context.hasEndpoint(endpointUri) ne null)

      when("a request is sent to this actor")
      val response1 = CamelContextManager.template.requestBody(endpointUri, "msg1")

      then("the direct endpoint falls back to its default behaviour and returns the original message")
      assert(response1 === "msg1")
    }
  }

  feature("Configure a custom Camel route for the global CamelContext") {

    scenario("access an actor from the custom Camel route") {

      given("a registered actor and a custom route to that actor")
      val actor = actorOf[TestActor].start

      when("sending a a message to that route")
      val response = CamelContextManager.template.requestBody("direct:custom-route-test-1", "msg3")

      then("an expected response generated by the actor should be returned")
      assert(response === "received msg3")
    }
  }

  feature("Publish active object methods in the global CamelContext") {

    scenario("access active object methods via Camel direct-endpoints") {

      given("an active object registered after CamelService startup")
      val latch = service.consumerPublisher.!![CountDownLatch](SetExpectedMessageCount(3)).get
      ActiveObject.newInstance(classOf[PojoBase])
      assert(latch.await(5000, TimeUnit.MILLISECONDS))

      when("requests are sent to published methods")
      val response1 = CamelContextManager.template.requestBodyAndHeader("direct:m2base", "x", "test", "y")
      val response2 = CamelContextManager.template.requestBodyAndHeader("direct:m3base", "x", "test", "y")
      val response3 = CamelContextManager.template.requestBodyAndHeader("direct:m4base", "x", "test", "y")

      then("each should have returned a different response")
      assert(response1 === "m2base: x y")
      assert(response2 === "m3base: x y")
      assert(response3 === "m4base: x y")
    }
  }
}

object CamelServiceFeatureTest {

  class TestConsumer(uri: String) extends Actor with Consumer {
    def endpointUri = uri
    protected def receive = {
      case msg: Message => self.reply("received %s" format msg.body)
    }
  }

  class TestActor extends Actor {
    self.id = "custom-actor-id"
    protected def receive = {
      case msg: Message => self.reply("received %s" format msg.body)
    }
  }

  class TestRoute extends RouteBuilder {
    def configure {
      from("direct:custom-route-test-1").to("actor:custom-actor-id")
    }
  }
}
