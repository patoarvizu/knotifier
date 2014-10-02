package actors

import java.util.ArrayList
import scala.collection.JavaConversions.asScalaBuffer
import scala.collection.JavaConversions.mapAsJavaConcurrentMap
import scala.collection.JavaConversions.mapAsJavaMap
import scala.collection.Map
import scala.collection.SortedMap
import scala.collection.mutable.HashMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.autoscaling.model.CreateAutoScalingGroupRequest
import com.amazonaws.services.autoscaling.model.CreateLaunchConfigurationRequest
import com.amazonaws.services.autoscaling.model.LaunchConfiguration
import com.amazonaws.services.autoscaling.model.Tag
import com.amazonaws.services.autoscaling.model.TagDescription
import com.amazonaws.services.autoscaling.model.UpdateAutoScalingGroupRequest
import com.amazonaws.services.ec2.model.InstanceType
import com.amazonaws.services.sqs.model.CreateQueueResult
import com.amazonaws.services.sqs.model.Message
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import com.amazonaws.services.sqs.model.ReceiveMessageResult
import com.amazonaws.util.json.JSONObject
import actors.AutoScalingDataMonitor.autoScalingGroups
import actors.AutoScalingDataMonitor.launchConfigurations
import actors.AutoScalingDataMonitor.updateSingleAutoScalingGroup
import actors.AutoScalingDataMonitor.updateSingleLaunchConfiguration
import akka.util.Timeout
import model.ReplacementInfo
import model.SpotPriceInfo
import play.Logger
import util.AmazonClient
import scala.util.matching.Regex

object AutoScaleModifier extends AmazonClient {

    final val BaseSpotGroupNameTag: String = "BaseSpotGroupName"
    final val GroupNameTag: String = "GroupName"
    private final val SpotGroupType: String = "Spot"
    private final val NotificationTypeField: String = "Event"
    private final val MessageField: String = "Message"
    private final val AutoScalingGroupIdField: String = "AutoScalingGroupName"
    private final val AutoScalingInstanceTerminateMessage: String = "autoscaling:EC2_INSTANCE_TERMINATE"
    private final val SpotPriceTag: String = "SpotPrice"
    private final val GroupTypeTag: String = "GroupType"
    private final val PreferredTypesTag: String = "PreferredTypes"
    private final val KnotifierQueueName: String = "knotifier-queue"

    private val spotReplacementInfoByGroup: HashMap[String, ReplacementInfo] = HashMap[String, ReplacementInfo]()

    def monitorAutoScaleGroups = {
        if(autoScalingGroups.isEmpty || launchConfigurations.isEmpty)
            Logger.debug("AutoScaling data is not ready yet")
        else {
            val queueResult: CreateQueueResult = sqsClient.createQueue(KnotifierQueueName)
            val sqsMessages: ReceiveMessageResult = sqsClient.receiveMessage(new ReceiveMessageRequest().withQueueUrl(queueResult.getQueueUrl))
            sqsMessages.getMessages foreach {sqsMessage: Message => processSQSMessage(sqsMessage)}
            spotReplacementInfoByGroup.keySet foreach { group: String => processReplacementInfo(group) }
            sqsMessages.getMessages foreach { sqsMessage => sqsClient.deleteMessage(queueResult.getQueueUrl, sqsMessage.getReceiptHandle)}
            spotReplacementInfoByGroup.clear
        }
    }

    private[this] def processSQSMessage(sqsMessage: Message) = {
        val message: JSONObject = new JSONObject(sqsMessage.getBody)
        val notification: JSONObject = new JSONObject(message.getString(MessageField))
        val groupName: String = getAutoScalingGroupName(notification.getString(AutoScalingGroupIdField))
        val autoScalingGroup: AutoScalingGroup = autoScalingGroups.getOrElse(groupName, throw new RuntimeException(s"Auto-scaling group groupName doesn't exist"))
        val groupType: Option[String] = getGroupTypeTag(autoScalingGroup.getTags)
        if(AutoScalingInstanceTerminateMessage == notification.getString(NotificationTypeField))
        {
            if(groupType == Some(SpotGroupType)) {
                spotReplacementInfoByGroup.get(groupName) match {
                    case Some(spotReplacementInfo) => {
                        spotReplacementInfoByGroup.put(groupName, spotReplacementInfo.copy(newInstances=spotReplacementInfo.instanceCount + 1))
                    }
                    case None =>
                        spotReplacementInfoByGroup.put(groupName,
                            ReplacementInfo(spotGroupName=groupName, autoScalingGroup=autoScalingGroup))
                }
            }
        }
    }

    private[this] def getGroupTypeTag(tags: Iterable[TagDescription]): Option[String] = {
        tags foreach { tag: TagDescription => if(tag.getKey == GroupTypeTag) return Some(tag.getValue) }
         None;
    }

    private[this] def getAutoScalingGroupName(autoScalingGroupId: String): String = {
        val idRegex = new Regex("""(.*)(-[0-9A-Z]{12,13})$""", "name", "ARNid")
        idRegex findFirstMatchIn autoScalingGroupId match {
            case Some(matched) => matched.group("name")
            case None => autoScalingGroupId
        }
    }

    private[this] def processReplacementInfo(spotGroupName: String) = {
        val replacementInfo: ReplacementInfo = spotReplacementInfoByGroup.getOrElse(spotGroupName,
                throw new RuntimeException(s"Replacement info object for group $spotGroupName wasn't found"))
        Logger.debug(s"Replacements needed for group $spotGroupName: ${replacementInfo.instanceCount}")
        val baseSpotGroupName: String = replacementInfo.baseSpotGroupName
        val newInstanceInfo: SpotPriceInfo = discoverNewInstanceInfo(replacementInfo.getTagValue(PreferredTypesTag))
        
        if(!launchConfigurations.containsKey(s"$baseSpotGroupName-${newInstanceInfo.instanceType}"))
        {
            val launchConfiguration: LaunchConfiguration = launchConfigurations.getOrElse(baseSpotGroupName,
                    throw new RuntimeException(s"Launch configuration $baseSpotGroupName wasn't found"))
            val createLaunchConfigurationRequest: CreateLaunchConfigurationRequest = composeNewLaunchConfigurationRequest(newInstanceInfo.instanceType.toString, replacementInfo, launchConfiguration)
            asClient.createLaunchConfiguration(createLaunchConfigurationRequest)
            updateSingleLaunchConfiguration(baseSpotGroupName)
        }
        if(autoScalingGroups.containsKey(s"$baseSpotGroupName-${newInstanceInfo.availabilityZone}"))
        {
            val autoScalingGroup: AutoScalingGroup = autoScalingGroups.getOrElse(s"$baseSpotGroupName-${newInstanceInfo.availabilityZone}",
                    throw new Exception(s"Auto scaling group $baseSpotGroupName-${newInstanceInfo.availabilityZone} wasn't found"))
            val updateAutoScalingGroupRequest: UpdateAutoScalingGroupRequest = new UpdateAutoScalingGroupRequest
            updateAutoScalingGroupRequest.setAutoScalingGroupName(autoScalingGroup.getAutoScalingGroupName)
            updateAutoScalingGroupRequest.setLaunchConfigurationName(s"${replacementInfo.baseSpotGroupName}-${newInstanceInfo.instanceType}")
            updateAutoScalingGroupRequest.setDesiredCapacity(replacementInfo.originalCapacity  + replacementInfo.instanceCount)
            asClient.updateAutoScalingGroup(updateAutoScalingGroupRequest)
            updateSingleAutoScalingGroup(s"$baseSpotGroupName-${newInstanceInfo.availabilityZone}")
        }
        else
            throw new RuntimeException(s"Auto scaling group $baseSpotGroupName-${newInstanceInfo.availabilityZone} wasn't found")
        if(autoScalingGroups.containsKey(spotGroupName))
        {
            val autoScalingGroup: AutoScalingGroup = autoScalingGroups.getOrElse(spotGroupName,
                    throw new Exception(s"Auto scaling group $spotGroupName wasn't found"))
            val updateAutoScalingGroupRequest: UpdateAutoScalingGroupRequest = new UpdateAutoScalingGroupRequest
            updateAutoScalingGroupRequest.setAutoScalingGroupName(autoScalingGroup.getAutoScalingGroupName)
            updateAutoScalingGroupRequest.setDesiredCapacity(replacementInfo.originalCapacity - replacementInfo.instanceCount)
            asClient.updateAutoScalingGroup(updateAutoScalingGroupRequest)
            updateSingleAutoScalingGroup(spotGroupName)
        }
        else
            throw new RuntimeException(s"Auto scaling group $spotGroupName wasn't found")
    }

    private[this] def discoverNewInstanceInfo(preferredTypes: String): SpotPriceInfo =
    {
        val preferredTypesSet: Set[String] = preferredTypes.split(",").toSet
        val weightedPrices: Map[InstanceType, SpotPriceInfo] = PriceMonitor.getWeightedPrices
        SortedMap[Double, SpotPriceInfo](weightedPrices.filterKeys({instanceType: InstanceType =>
        preferredTypesSet.contains(instanceType.toString)
        }).collect({
            case (instanceType: InstanceType, spotPriceInfo: SpotPriceInfo) => spotPriceInfo.price -> spotPriceInfo
        }).toSeq:_*).head._2
    }

    private[this] def composeNewLaunchConfigurationRequest(instanceType: String, replacementInfo: ReplacementInfo, launchConfiguration: LaunchConfiguration): CreateLaunchConfigurationRequest =
    {
        val createLaunchConfigurationRequest: CreateLaunchConfigurationRequest = new CreateLaunchConfigurationRequest
        createLaunchConfigurationRequest.setImageId(launchConfiguration.getImageId)
        createLaunchConfigurationRequest.setKeyName(launchConfiguration.getKeyName)
        createLaunchConfigurationRequest.setSecurityGroups(launchConfiguration.getSecurityGroups)
        createLaunchConfigurationRequest.setUserData(launchConfiguration.getUserData)
        createLaunchConfigurationRequest.setInstanceType(instanceType)
        createLaunchConfigurationRequest.setSpotPrice(replacementInfo.getTagValue(SpotPriceTag))
        createLaunchConfigurationRequest.setLaunchConfigurationName(newInstanceTypeLaunchConfigurationName(replacementInfo.baseSpotGroupName, instanceType))
        createLaunchConfigurationRequest
    }

    private[this] def newInstanceTypeLaunchConfigurationName(baseLaunchConfigurationName: String, instanceType: String): String =
    {
        return s"$baseLaunchConfigurationName-$instanceType"
    }
}