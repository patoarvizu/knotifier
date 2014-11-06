package actors

import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.autoscaling.model.LaunchConfiguration
import scala.collection.JavaConversions._
import scala.collection.concurrent.Map
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult
import com.amazonaws.services.autoscaling.model.DescribeLaunchConfigurationsResult
import util.AmazonClient
import scala.concurrent.ExecutionContext.Implicits.global
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest
import com.amazonaws.services.autoscaling.model.DescribeLaunchConfigurationsRequest
import util.NameHelper._
import com.amazonaws.services.autoscaling.AmazonAutoScalingAsyncClient
import util.NameHelper
import play.Logger
import scala.concurrent.Await
import scala.concurrent.duration.Duration

object AutoScalingDataMonitor {
    val launchConfigurations: Map[String, LaunchConfiguration] = new TrieMap[String, LaunchConfiguration]
    val autoScalingGroups: Map[String, AutoScalingGroup] = new TrieMap[String, AutoScalingGroup];
}

class AutoScalingDataMonitor extends AmazonClient {

    private final val nameHelper: NameHelper = new NameHelper

    val launchConfigurations = AutoScalingDataMonitor.launchConfigurations
    val autoScalingGroups = AutoScalingDataMonitor.autoScalingGroups

    def monitorAutoScalingData = {
        val autoScalingGroupsFuture = Future { asClient.describeAutoScalingGroups.getAutoScalingGroups foreach putAutoScalingGroupInMap }
        val launchConfigurationsFuture = Future { asClient.describeLaunchConfigurations.getLaunchConfigurations foreach putLaunchConfigurationInMap }
        Await.result(autoScalingGroupsFuture, Duration.Inf)
        Await.result(launchConfigurationsFuture, Duration.Inf)
    }

    def updateAutoScalingGroupsData = {
        val autoScalingGroupsResult: DescribeAutoScalingGroupsResult = asClient.describeAutoScalingGroups
        autoScalingGroupsResult.getAutoScalingGroups foreach putAutoScalingGroupInMap
    }

    def getAutoScalingGroupByAWSName(awsName: String): Option[AutoScalingGroup] = {
        val localAutoScalingGroupOption: Option[AutoScalingGroup] = autoScalingGroups.values.find({ autoScalingGroup: AutoScalingGroup => autoScalingGroup.getAutoScalingGroupName == awsName})
        localAutoScalingGroupOption match {
            case Some(autoScalingGroup) => Some(autoScalingGroup)
            case None => {
                val autoScalingGroupResult = asClient.describeAutoScalingGroups(new DescribeAutoScalingGroupsRequest withAutoScalingGroupNames(awsName))
                if(!autoScalingGroupResult.getAutoScalingGroups.isEmpty)
                {
                    val autoScalingGroup = autoScalingGroupResult.getAutoScalingGroups.get(0)
                    putAutoScalingGroupInMap(autoScalingGroup)
                    Some(autoScalingGroup)
                }
                else
                    return None;
            }
        }
    }

    def updateSingleAutoScalingGroup(autoScalingGroupName: String) = {
        val autoScalingGroup: AutoScalingGroup = autoScalingGroups(autoScalingGroupName)
        val autoScalingGroupResult: DescribeAutoScalingGroupsResult =
            asClient.describeAutoScalingGroups(new DescribeAutoScalingGroupsRequest withAutoScalingGroupNames(autoScalingGroup.getAutoScalingGroupName))
        if(!autoScalingGroupResult.getAutoScalingGroups.isEmpty)
            putAutoScalingGroupInMap(autoScalingGroupResult.getAutoScalingGroups.get(0))
    }

    private def putAutoScalingGroupInMap(autoScalingGroup: AutoScalingGroup) = {
        val mapIndex = nameHelper.getAutoScalingGroupsMapIndex(autoScalingGroup)
        autoScalingGroups(mapIndex) = autoScalingGroup
    }

    private def putLaunchConfigurationInMap(launchConfiguration: LaunchConfiguration) = {
        val mapIndex = nameHelper.getLaunchConfigurationsMapIndex(launchConfiguration);
        launchConfigurations(mapIndex) = launchConfiguration
    }

    private[actors] def clearAutoScalingData = {
        autoScalingGroups.clear
        launchConfigurations.clear
    }
}