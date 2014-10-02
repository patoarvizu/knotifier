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
import play.Logger
import com.amazonaws.services.autoscaling.model.TagDescription
import scala.util.matching.Regex
import scala.util.matching.Regex.Match
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest
import com.amazonaws.services.autoscaling.model.DescribeLaunchConfigurationsRequest

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
        Future { asClient.describeAutoScalingGroups(new DescribeAutoScalingGroupsRequest withAutoScalingGroupNames(autoScalingGroup.getAutoScalingGroupName)) } onSuccess {
            case autoScalingGroupResult: DescribeAutoScalingGroupsResult =>
                if(!autoScalingGroupResult.getAutoScalingGroups.isEmpty)
                    putAutoScalingGroupInMap(autoScalingGroupResult.getAutoScalingGroups.get(0))
        }
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

    private[this] def getAutoScalingGroupsMapIndex(autoScalingGroup: AutoScalingGroup): String = {
        val tags: Iterable[TagDescription] = autoScalingGroup.getTags
        if(autoScalingGroup.getAutoScalingGroupName.toLowerCase contains "spot")
        {
            tags foreach { tagDescription => if(tagDescription.getKey == AutoScaleModifier.GroupNameTag ) return tagDescription.getValue}
        }
        val idRegex = new Regex("""(.*)(-[0-9A-Z]{12,13})$""", "name", "ARNid")
        idRegex findFirstMatchIn autoScalingGroup.getAutoScalingGroupName match {
            case Some(matched) => matched.group("name")
            case None => autoScalingGroup.getAutoScalingGroupName
        }
    }

    private[this] def getLaunchConfigurationsMapIndex(launchConfiguration: LaunchConfiguration): String = {
        val idRegex = new Regex("""(.*)(-[0-9A-Z]{12,13})$""", "name", "ARNid")
        idRegex findFirstMatchIn launchConfiguration.getLaunchConfigurationName match {
            case Some(matched) => matched.group("name")
            case None => launchConfiguration.getLaunchConfigurationName
        }
    }
}