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

object AutoScalingDataMonitor extends AmazonClient {

    val autoScalingGroups: Map[String, AutoScalingGroup] = new TrieMap[String, AutoScalingGroup];
    val launchConfigurations: Map[String, LaunchConfiguration] = new TrieMap[String, LaunchConfiguration]

    def monitorAutoScalingData = {
        val autoScalingGroupsFuture: Future[DescribeAutoScalingGroupsResult] = Future { asClient.describeAutoScalingGroups }
        autoScalingGroupsFuture onSuccess {
            case result: DescribeAutoScalingGroupsResult => {
                result.getAutoScalingGroups foreach putAutoScalingGroupInMap
            }
        }
        val launchConfigurationsFuture: Future[DescribeLaunchConfigurationsResult] = Future { asClient.describeLaunchConfigurations }
        launchConfigurationsFuture onSuccess {
            case result: DescribeLaunchConfigurationsResult => {
                result.getLaunchConfigurations foreach putLaunchConfigurationInMap
            }
        }
    }

    def updateSingleAutoScalingGroup(autoScalingGroupName: String) = {
        val autoScalingGroup: AutoScalingGroup = autoScalingGroups(autoScalingGroupName)
        val autoScalingGroupResult: DescribeAutoScalingGroupsResult = asClient.describeAutoScalingGroups(new DescribeAutoScalingGroupsRequest withAutoScalingGroupNames(autoScalingGroup.getAutoScalingGroupName))
        if(!autoScalingGroupResult.getAutoScalingGroups.isEmpty)
            putAutoScalingGroupInMap(autoScalingGroupResult.getAutoScalingGroups.get(0))
    }

    def updateSingleLaunchConfiguration(launchConfigurationName: String) = {
        val launchConfiguration: LaunchConfiguration = launchConfigurations(launchConfigurationName)
        Future { asClient.describeLaunchConfigurations(new DescribeLaunchConfigurationsRequest withLaunchConfigurationNames(launchConfiguration.getLaunchConfigurationName)) } onSuccess {
            case launchConfigurationResult: DescribeLaunchConfigurationsResult =>
                if(!launchConfigurationResult.getLaunchConfigurations.isEmpty)
                    putLaunchConfigurationInMap(launchConfigurationResult.getLaunchConfigurations.get(0))
        }
    }

    def updateAutoScalingGroupsData = {
        val autoScalingGroupsResult: DescribeAutoScalingGroupsResult = asClient.describeAutoScalingGroups
        autoScalingGroupsResult.getAutoScalingGroups foreach putAutoScalingGroupInMap
    }

    def updateLaunchConfigurationsData() = {
        val launchConfigurationsResult: DescribeLaunchConfigurationsResult = asClient.describeLaunchConfigurations()
        launchConfigurationsResult.getLaunchConfigurations foreach putLaunchConfigurationInMap
    }

    private[this] def putAutoScalingGroupInMap(autoScalingGroup: AutoScalingGroup) = {
        val mapIndex = getAutoScalingGroupsMapIndex(autoScalingGroup)
        autoScalingGroups(mapIndex) = autoScalingGroup
    }

    private[this] def putLaunchConfigurationInMap(launchConfiguration: LaunchConfiguration) = {
        val mapIndex = getLaunchConfigurationsMapIndex(launchConfiguration);
        launchConfigurations(mapIndex) = launchConfiguration
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
}