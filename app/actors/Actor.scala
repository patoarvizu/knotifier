package actors

import scala.concurrent.ExecutionContext
import play.libs.Akka
import akka.actor.ActorContext
import akka.actor.TypedActor

trait Actor {
    val typedActorContext: ActorContext = TypedActor.context
}