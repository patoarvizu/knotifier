package util;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.autoscaling.AmazonAutoScalingAsyncClient;
import com.amazonaws.services.ec2.AmazonEC2AsyncClient;
import com.amazonaws.services.sqs.AmazonSQSAsyncClient;

public interface BaseAmazonClient
{
    public AWSCredentials credentials = new ProfileCredentialsProvider().getCredentials();
    public AmazonSQSAsyncClient sqsClient = new AmazonSQSAsyncClient(credentials);
    public AmazonAutoScalingAsyncClient asClient = new AmazonAutoScalingAsyncClient(credentials);
    public AmazonEC2AsyncClient ec2ClientAsync = new AmazonEC2AsyncClient(credentials);
}
