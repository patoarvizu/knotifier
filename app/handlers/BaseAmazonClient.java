package handlers;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.autoscaling.AmazonAutoScalingAsyncClient;
import com.amazonaws.services.sqs.AmazonSQSAsyncClient;

public abstract class BaseAmazonClient
{
    protected AWSCredentials credentials = new ProfileCredentialsProvider().getCredentials();
    protected AmazonSQSAsyncClient sqsClient = new AmazonSQSAsyncClient(credentials);
    protected AmazonAutoScalingAsyncClient asClient = new AmazonAutoScalingAsyncClient(credentials);
}
