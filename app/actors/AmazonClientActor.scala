package actors

import util.BaseAmazonClient
import akka.actor.ActorContext
import akka.actor.TypedActor

trait AmazonClientActor extends BaseAmazonClient with BaseActor {
    val typedActorContext: ActorContext = TypedActor.context;
}