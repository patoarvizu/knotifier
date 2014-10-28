package actors

import org.specs2.mock.Mockito
import org.mockito.Matchers.{ eq => mockitoEq }
import org.specs2.mutable.Specification
import com.amazonaws.services.sqs.AmazonSQSAsyncClient
import scala.collection.concurrent.TrieMap
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.autoscaling.model.LaunchConfiguration
import com.amazonaws.services.sqs.model.CreateQueueResult
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import com.amazonaws.services.sqs.model.ReceiveMessageResult
import com.amazonaws.services.autoscaling.AmazonAutoScalingAsyncClient
import com.amazonaws.services.sqs.model.Message
import com.amazonaws.services.autoscaling.model.TagDescription
import util.NameHelper
import com.amazonaws.services.ec2.model.InstanceType.M3Medium
import model.SpotPriceInfo
import com.amazonaws.services.autoscaling.model.CreateLaunchConfigurationRequest
import com.amazonaws.services.autoscaling.model.UpdateAutoScalingGroupRequest
import org.mockito.ArgumentMatcher
import org.specs2.matcher.ValueCheck.valueIsTypedValueCheck
import org.specs2.matcher.Hamcrest

class AutoScaleModifierTest extends Specification with Mockito with Hamcrest {
    isolated
    val mockPriceMonitor: PriceMonitor = mock[PriceMonitor];
    val mockAutoScalingDataMonitor = mock[AutoScalingDataMonitor];
    mockAutoScalingDataMonitor.autoScalingGroups returns new TrieMap[String, AutoScalingGroup];
    mockAutoScalingDataMonitor.launchConfigurations returns new TrieMap[String, LaunchConfiguration];
    val mockSQSClient: AmazonSQSAsyncClient = mock[AmazonSQSAsyncClient];
    mockSQSClient.createQueue(anyString) returns new CreateQueueResult().withQueueUrl("knotifierQueueUrl")
    mockSQSClient.receiveMessage(any[ReceiveMessageRequest]) returns new ReceiveMessageResult()
    val mockASClient: AmazonAutoScalingAsyncClient = mock[AmazonAutoScalingAsyncClient]
    val mockNameHelper: NameHelper = mock[NameHelper]
    val autoScaleModifierSpy: AutoScaleModifier = spy(new AutoScaleModifier(mockAutoScalingDataMonitor, mockPriceMonitor, mockNameHelper));
    autoScaleModifierSpy.sqsClient returns mockSQSClient;
    autoScaleModifierSpy.asClient returns mockASClient

    "The auto scale modifier actor" should {
        "Do nothing if either the launch configurations or auto scaling groups local cache is not available" in {
            autoScaleModifierSpy.monitorAutoScaleGroups
            there were noCallsTo(mockSQSClient)
        }
    }
    "The auto scale monitor" should {
        "Not make any processing if the queue contains no messages" in {
            autoScaleModifierSpy.monitorAutoScaleGroups
            there were noMoreCallsTo(mockSQSClient)
            there were noCallsTo(mockASClient)
        }
        "Not process and delete any messages that are not EC2_INSTANCE_TERMINATE" in {
            mockAutoScalingDataMonitor.autoScalingGroups returns TrieMap[String, AutoScalingGroup]("autoScalingGroup" -> new AutoScalingGroup);
            mockAutoScalingDataMonitor.launchConfigurations returns TrieMap[String, LaunchConfiguration]("launchConfiguration" -> new LaunchConfiguration)
            mockSQSClient.receiveMessage(any[ReceiveMessageRequest]) returns new ReceiveMessageResult().withMessages(createMessage("autoScalingGroup", AutoScaleModifier.AutoScalingInstanceTerminateMessage + "-NOT"))
            autoScaleModifierSpy.monitorAutoScaleGroups
            there was one(mockSQSClient).deleteMessage(anyString, anyString)
            there were noCallsTo(mockASClient)
        }
        "Can replace an instance of the same type in a different availability zone if the price is lower" in {
            val stackTag = new TagDescription().withKey(NameHelper.StackNameTag).withValue("stackName")
            val systemTag = new TagDescription().withKey(NameHelper.SystemTag).withValue("system")
            val groupTypeTag = new TagDescription().withKey(NameHelper.GroupTypeTag).withValue("Spot")
            val preferredTypesTag = new TagDescription().withKey(NameHelper.PreferredTypesTag).withValue("m3.medium")
            val spotPriceTag = new TagDescription().withKey(NameHelper.SpotPriceTag).withValue("0.01")
            val availabilityZoneATag = new TagDescription().withKey(NameHelper.AvailabilityZoneTag).withValue("us-east-1a")
            val availabilityZoneDTag = new TagDescription().withKey(NameHelper.AvailabilityZoneTag).withValue("us-east-1d")
            val autoScalingGroupA = new AutoScalingGroup().withAutoScalingGroupName("stackName-systemASScalingGroupSpotuseast1a-ABCDEF123456").withTags(stackTag, systemTag, groupTypeTag, preferredTypesTag, spotPriceTag, availabilityZoneATag).withDesiredCapacity(1).withMaxSize(2).withMinSize(0)
            val autoScalingGroupD = new AutoScalingGroup().withAutoScalingGroupName("stackName-systemASScalingGroupSpotuseast1d-ABCDEF123456").withTags(stackTag, systemTag, groupTypeTag, preferredTypesTag, spotPriceTag, availabilityZoneDTag).withDesiredCapacity(1).withMaxSize(2).withMinSize(0)
            mockAutoScalingDataMonitor.autoScalingGroups returns TrieMap[String, AutoScalingGroup]("stackName-systemASScalingGroupSpot-us-east-1a" -> autoScalingGroupA, "stackName-systemASScalingGroupSpot-us-east-1d" -> autoScalingGroupD);
            mockAutoScalingDataMonitor.launchConfigurations returns TrieMap[String, LaunchConfiguration]("stackName-systemASLaunchConfigurationSpot" -> new LaunchConfiguration().withLaunchConfigurationName("stackName-systemASLaunchConfigurationSpot"))
            mockAutoScalingDataMonitor.getAutoScalingGroupByAWSName(mockitoEq("stackName-systemASScalingGroupSpotuseast1a-ABCDEF123456")) returns Some(autoScalingGroupA)
            mockNameHelper.getAutoScalingGroupsMapIndex(mockitoEq(autoScalingGroupA)) returns "stackName-systemASScalingGroupSpot-us-east-1a"
            mockNameHelper.getTagValue(any[List[TagDescription]], mockitoEq(NameHelper.GroupTypeTag)) returns AutoScaleModifier.SpotGroupType
            mockSQSClient.receiveMessage(any[ReceiveMessageRequest]) returns new ReceiveMessageResult().withMessages(createMessage("stackName-systemASScalingGroupSpotuseast1a-ABCDEF123456", AutoScaleModifier.AutoScalingInstanceTerminateMessage))
            mockPriceMonitor.getWeightedPrices returns Map(M3Medium -> SpotPriceInfo(M3Medium, "us-east-1d", 0.01))
            mockNameHelper.getAutoScalingGroupNameWithAvailabilityZone("stackName-systemASScalingGroupSpot", "us-east-1d") returns "stackName-systemASScalingGroupSpot-us-east-1d"
            mockNameHelper.getLaunchConfigurationNameWithInstanceType("stackName-systemASLaunchConfigurationSpot", "m3.medium") returns "stackName-systemASLaunchConfigurationSpot-m3.medium"
            autoScaleModifierSpy.monitorAutoScaleGroups
            there was one(mockAutoScalingDataMonitor).updateSingleAutoScalingGroup(mockitoEq("stackName-systemASScalingGroupSpot-us-east-1d"))
            there was one(mockAutoScalingDataMonitor).updateSingleAutoScalingGroup(mockitoEq("stackName-systemASScalingGroupSpot-us-east-1a"))
            there was one(mockASClient).createLaunchConfiguration(argThat({ request: CreateLaunchConfigurationRequest => request.getLaunchConfigurationName == "stackName-systemASLaunchConfigurationSpot-m3.medium" }))
            there was one(mockASClient).updateAutoScalingGroup(argThat({ request: UpdateAutoScalingGroupRequest => request.getAutoScalingGroupName == "stackName-systemASScalingGroupSpotuseast1a-ABCDEF123456" && request.getDesiredCapacity == 0 }))
            there was one(mockASClient).updateAutoScalingGroup(argThat({ request: UpdateAutoScalingGroupRequest => request.getAutoScalingGroupName == "stackName-systemASScalingGroupSpotuseast1d-ABCDEF123456" && request.getDesiredCapacity == 2 }))
            
        }
        "Skips replacement if it cannot find the AWS group that needs replacements" in {
            todo
        }
    }
    
    private def createMessage(autoScalingGroupName: String, terminationMessage: String): Message = {
        new Message().withBody(s"""
        {
          "Type" : "Notification",
          "MessageId" : "2bcd7eac-3de8-54ff-9162-3269ea10af33",
          "TopicArn" : "arn:aws:sns:us-east-1:123456789012:knotifier",
          "Subject" : "Auto Scaling: termination for group \\"$autoScalingGroupName\\"",
          "Message" : "{\\"StatusCode\\":\\"InProgress\\",\\"Service\\":\\"AWS Auto Scaling\\",\\"AutoScalingGroupName\\":\\"$autoScalingGroupName\\",\\"Description\\":\\"Terminating EC2 instance: i-xxxxxxxx\\",\\"ActivityId\\":\\"8d42db65-9734-467c-a5f0-60a01d62e506\\",\\"Event\\":\\"$terminationMessage\\",\\"Details\\":{\\"Availability Zone\\":\\"us-east-1a\\"},\\"AutoScalingGroupARN\\":\\"arn:aws:autoscaling:us-east-1:123456789012:autoScalingGroup:61b4fcdd-5706-4fc7-a5a3-611febbe3434:autoScalingGroupName/$autoScalingGroupName\\",\\"Progress\\":50,\\"Time\\":\\"2014-10-25T16:54:16.157Z\\",\\"AccountId\\":\\"123456789012\\",\\"RequestId\\":\\"8d42db65-9734-467c-a5f0-60a01d62e506\\",\\"StatusMessage\\":\\"\\",\\"EndTime\\":\\"2014-10-25T16:54:16.157Z\\",\\"EC2InstanceId\\":\\"i-xxxxxxxx\\",\\"StartTime\\":\\"2014-10-25T16:54:08.609Z\\",\\"Cause\\":\\"At 2014-10-25T16:54:08Z an instance was taken out of service in response to a EC2 health check indicating it has been terminated or stopped.\\"}",
          "Timestamp" : "2014-10-25T16:54:16.274Z",
          "SignatureVersion" : "1",
          "Signature" : "ABCXYZ",
          "SigningCertURL" : "https://sns.us-east-1.amazonaws.com/SimpleNotificationService-abc123.pem",
          "UnsubscribeURL" : "https://sns.us-east-1.amazonaws.com/?Action=Unsubscribe&SubscriptionArn=arn:aws:sns:us-east-1:123456789012:knotifier:27c845fa-690f-49d5-8e2f-21ee68647b31"
        }""").withReceiptHandle("receiptHandle")
    }
}