package util

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.sqs.AmazonSQSAsyncClient
import com.amazonaws.services.autoscaling.AmazonAutoScalingAsyncClient
import com.amazonaws.services.ec2.AmazonEC2AsyncClient

trait AmazonClient {
	final val credentials: AWSCredentials = new ProfileCredentialsProvider().getCredentials
	final val sqsClient: AmazonSQSAsyncClient = new AmazonSQSAsyncClient(credentials)
	final val asClient: AmazonAutoScalingAsyncClient = new AmazonAutoScalingAsyncClient(credentials)
	final val ec2ClientAsync: AmazonEC2AsyncClient = new AmazonEC2AsyncClient(credentials)
}