package actors;

import akka.actor.ActorContext;
import akka.actor.TypedActor;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.autoscaling.AmazonAutoScalingAsyncClient;
import com.amazonaws.services.ec2.AmazonEC2AsyncClient;
import com.amazonaws.services.sqs.AmazonSQSAsyncClient;

public interface AmazonClientActor
{
    public final AWSCredentials credentials = new ProfileCredentialsProvider().getCredentials();
    public final AmazonSQSAsyncClient sqsClient = new AmazonSQSAsyncClient(credentials);
    public final AmazonAutoScalingAsyncClient asClient = new AmazonAutoScalingAsyncClient(credentials);
    public final AmazonEC2AsyncClient ec2ClientAsync = new AmazonEC2AsyncClient(credentials);
    public final ActorContext typedActorContext = TypedActor.context();
}
