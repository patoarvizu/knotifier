package actors;

import akka.actor.ActorContext;
import akka.actor.TypedActor;

import util.BaseAmazonClient;

public interface AmazonClientActor extends BaseAmazonClient
{
    public final ActorContext typedActorContext = TypedActor.context();
}
