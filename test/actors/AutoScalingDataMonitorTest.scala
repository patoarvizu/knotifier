package actors

import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import com.amazonaws.services.sqs.AmazonSQSAsyncClient
import com.amazonaws.services.autoscaling.AmazonAutoScalingAsyncClient
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult
import java.util.ArrayList
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.autoscaling.model.DescribeLaunchConfigurationsResult
import com.amazonaws.services.autoscaling.model.LaunchConfiguration
import com.amazonaws.services.autoscaling.model.DescribeLaunchConfigurationsRequest
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest
import com.amazonaws.services.autoscaling.model.TagDescription

class AutoScalingDataMonitorTest extends Specification with Mockito {
    isolated
    val autoScalingDataMonitor: AutoScalingDataMonitor = spy(new AutoScalingDataMonitor)
    val mockSQSClient: AmazonSQSAsyncClient = mock[AmazonSQSAsyncClient]
    val mockASClient: AmazonAutoScalingAsyncClient = mock[AmazonAutoScalingAsyncClient]
    autoScalingDataMonitor.sqsClient returns mockSQSClient
    autoScalingDataMonitor.asClient returns mockASClient
    mockASClient.describeAutoScalingGroups returns new DescribeAutoScalingGroupsResult().withAutoScalingGroups(new ArrayList[AutoScalingGroup])
    mockASClient.describeLaunchConfigurations returns new DescribeLaunchConfigurationsResult().withLaunchConfigurations(new ArrayList[LaunchConfiguration])
    
    "The auto scaling data monitor" should {
        "Be able to update auto scaling groups cache data" in {
            val autoScalingGroupBefore = new AutoScalingGroup()
                    .withAutoScalingGroupName("autoScalingGroup")
            mockASClient.describeAutoScalingGroups returns new DescribeAutoScalingGroupsResult()
                .withAutoScalingGroups(autoScalingGroupBefore)
            autoScalingDataMonitor.monitorAutoScalingData
            autoScalingDataMonitor.autoScalingGroups("autoScalingGroup") must beTheSameAs(autoScalingGroupBefore)
            val autoScalingGroupAfter = new AutoScalingGroup()
                    .withAutoScalingGroupName("autoScalingGroup")
            mockASClient.describeAutoScalingGroups(any[DescribeAutoScalingGroupsRequest]) returns new DescribeAutoScalingGroupsResult()
                .withAutoScalingGroups(autoScalingGroupAfter)
            autoScalingDataMonitor.updateSingleAutoScalingGroup("autoScalingGroup")
            autoScalingDataMonitor.autoScalingGroups("autoScalingGroup") must beTheSameAs(autoScalingGroupAfter)
        }
    }
    
    "The auto scaling data monitor cache" should {
        autoScalingDataMonitor.clearAutoScalingData
        autoScalingDataMonitor.monitorAutoScalingData
        "Have an empty auto scaling groups map when there are no auto scaling groups" in {
            AutoScalingDataMonitor.autoScalingGroups must beEmpty
        }
        "Have an empty launch configurations map when there are no launch configurations" in {
            AutoScalingDataMonitor.launchConfigurations must beEmpty
        }
    }
    
    "The auto scaling data monitor actor" should {
        autoScalingDataMonitor.clearAutoScalingData
        "Be able to find an auto scaling group by name when it's in the cache" in {
            val autoScalingGroup = new AutoScalingGroup()
                    .withAutoScalingGroupName("autoScalingGroup")
            mockASClient.describeAutoScalingGroups returns new DescribeAutoScalingGroupsResult().withAutoScalingGroups(autoScalingGroup)
            autoScalingDataMonitor.monitorAutoScalingData
            autoScalingDataMonitor.getAutoScalingGroupByAWSName("autoScalingGroup") must beSome(beTheSameAs(autoScalingGroup))
        }
        "Return None if an auto scaling group can't be found by name in the cache or via the API" in {
            mockASClient.describeAutoScalingGroups(any[DescribeAutoScalingGroupsRequest]) returns new DescribeAutoScalingGroupsResult()
            autoScalingDataMonitor.getAutoScalingGroupByAWSName("autoScalingGroup") must beNone
        }
        "Be able to find an auto scaling group by name using the AWS API when it's not in the cache" in {
            val autoScalingGroup = new AutoScalingGroup()
                    .withAutoScalingGroupName("autoScalingGroup")
            mockASClient.describeAutoScalingGroups(any[DescribeAutoScalingGroupsRequest]) returns new DescribeAutoScalingGroupsResult().withAutoScalingGroups(autoScalingGroup)
            autoScalingDataMonitor.getAutoScalingGroupByAWSName("autoScalingGroup") must beSome(beTheSameAs(autoScalingGroup))
        }
    }
    
    "Updating the auto scaling groups data" should {
        autoScalingDataMonitor.clearAutoScalingData
        "Update the local cache if any new auto scaling groups are found by the API" in {
            AutoScalingDataMonitor.autoScalingGroups must beEmpty
            val autoScalingGroup = new AutoScalingGroup()
                    .withAutoScalingGroupName("autoScalingGroup")
            mockASClient.describeAutoScalingGroups returns new DescribeAutoScalingGroupsResult().withAutoScalingGroups(autoScalingGroup)
            autoScalingDataMonitor.updateAutoScalingGroupsData
            AutoScalingDataMonitor.autoScalingGroups must havePair("autoScalingGroup" -> autoScalingGroup)
        }
    }
}