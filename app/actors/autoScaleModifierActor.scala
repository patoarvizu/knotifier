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
import actors.AutoScalingDataMonitor._
import akka.util.Timeout
import model.ReplacementInfo
import model.SpotPriceInfo
import play.Logger
import util.AmazonClient
import scala.util.matching.Regex
import util.NameHelper._

object AutoScaleModifier extends AmazonClient {

    private final val SpotGroupType: String = "Spot"
    private final val NotificationTypeField: String = "Event"
    private final val MessageField: String = "Message"
    private final val AutoScalingGroupIdField: String = "AutoScalingGroupName"
    private final val AutoScalingInstanceTerminateMessage: String = "autoscaling:EC2_INSTANCE_TERMINATE"
    private final val GroupTypeTag: String = "GroupType"
    private final val KnotifierQueueName: String = "knotifier-queue"

    private val spotReplacementInfoByGroup: HashMap[String, ReplacementInfo] = HashMap[String, ReplacementInfo]()

    def monitorAutoScaleGroups = {
        (autoScalingGroups.isEmpty || launchConfigurations.isEmpty) match {
            case true => Logger.debug("AutoScaling data is not ready yet")
            case false => {
                val queueResult: CreateQueueResult = sqsClient.createQueue(KnotifierQueueName)
                val sqsMessages: ReceiveMessageResult = sqsClient.receiveMessage(new ReceiveMessageRequest().withQueueUrl(queueResult.getQueueUrl))
                sqsMessages.getMessages foreach {sqsMessage: Message => processSQSMessage(sqsMessage)}
                spotReplacementInfoByGroup.keySet foreach { group: String => processReplacementInfo(group) }
                sqsMessages.getMessages foreach { sqsMessage => sqsClient.deleteMessage(queueResult.getQueueUrl, sqsMessage.getReceiptHandle)}
                spotReplacementInfoByGroup.clear
            }
        }
    }

    private[this] def processSQSMessage(sqsMessage: Message): Unit = {
        val message: JSONObject = new JSONObject(sqsMessage.getBody)
        val notification: JSONObject = new JSONObject(message.getString(MessageField))
        if(AutoScalingInstanceTerminateMessage == notification.getString(NotificationTypeField))
        {
            val awsGroupName: String = notification.getString(AutoScalingGroupIdField)
            val autoScalingGroup: AutoScalingGroup = getAutoScalingGroupByAWSName(awsGroupName) match {
                case Some(autoScalingGroup) => autoScalingGroup
                case None => {
                    Logger.error(s"Auto scaling group $awsGroupName not found, skipping")
                    return;
                }
            }
            val groupName: String = getAutoScalingGroupsMapIndex(autoScalingGroup)
            val groupType: Option[String] = getGroupTypeTag(autoScalingGroup.getTags)
            if(groupType == Some(SpotGroupType))
                spotReplacementInfoByGroup.get(groupName) match {
                    case Some(spotReplacementInfo) => spotReplacementInfoByGroup.put(groupName, spotReplacementInfo.copy(newInstances=spotReplacementInfo.instanceCount + 1))
                    case None => spotReplacementInfoByGroup.put(groupName, ReplacementInfo(spotGroupName=groupName, autoScalingGroup=autoScalingGroup))
                }
        }
    }

    private[this] def getGroupTypeTag(tags: Iterable[TagDescription]): Option[String] = {
        tags.find({ tag: TagDescription => tag.getKey == GroupTypeTag}) match {
            case Some(tag) => Some(tag.getValue)
            case None => None
        }
    }

    private[this] def processReplacementInfo(spotGroupName: String) = {
        val replacementInfo: ReplacementInfo = spotReplacementInfoByGroup.get(spotGroupName).get
        Logger.info(s"Replacements needed for group $spotGroupName: ${replacementInfo.instanceCount}")
        val baseSpotGroupName: String = replacementInfo.baseSpotGroupName
        val baseLaunchConfigurationName: String = replacementInfo.baseLaunchConfigurationName
        val newInstanceInfo: SpotPriceInfo = discoverNewInstanceInfo(replacementInfo.getTagValue(PreferredTypesTag))
        
        if(!launchConfigurations.containsKey(getLaunchConfigurationNameWithInstanceType(baseLaunchConfigurationName, s"${newInstanceInfo.instanceType}")))
        {
            val launchConfiguration: LaunchConfiguration = launchConfigurations.getOrElse(baseLaunchConfigurationName,
                    throw new RuntimeException(s"Launch configuration $baseLaunchConfigurationName wasn't found"))
            val createLaunchConfigurationRequest: CreateLaunchConfigurationRequest = composeNewLaunchConfigurationRequest(newInstanceInfo.instanceType, replacementInfo, launchConfiguration)
            asClient.createLaunchConfiguration(createLaunchConfigurationRequest)
        }
        if(autoScalingGroups.containsKey(getAutoScalingGroupNameWithAvailabilityZone(baseSpotGroupName, newInstanceInfo.availabilityZone)))
        {
            val autoScalingGroup: AutoScalingGroup = autoScalingGroups.get(getAutoScalingGroupNameWithAvailabilityZone(baseSpotGroupName, newInstanceInfo.availabilityZone)).get
            val updateAutoScalingGroupRequest: UpdateAutoScalingGroupRequest = new UpdateAutoScalingGroupRequest
            updateAutoScalingGroupRequest.setAutoScalingGroupName(autoScalingGroup.getAutoScalingGroupName)
            updateAutoScalingGroupRequest.setLaunchConfigurationName(s"$baseLaunchConfigurationName-${newInstanceInfo.instanceType}")
            updateAutoScalingGroupRequest.setDesiredCapacity((autoScalingGroup.getDesiredCapacity + replacementInfo.instanceCount).min(autoScalingGroup.getMaxSize))
            asClient.updateAutoScalingGroup(updateAutoScalingGroupRequest)
            updateSingleAutoScalingGroup(s"$baseSpotGroupName-${newInstanceInfo.availabilityZone}")
        }
        else
            throw new RuntimeException(s"Auto scaling group ${getAutoScalingGroupNameWithAvailabilityZone(baseSpotGroupName, newInstanceInfo.availabilityZone)} wasn't found")
        if(autoScalingGroups.containsKey(spotGroupName))
        {
            val autoScalingGroup: AutoScalingGroup = autoScalingGroups.get(spotGroupName).get
            val updateAutoScalingGroupRequest: UpdateAutoScalingGroupRequest = new UpdateAutoScalingGroupRequest
            updateAutoScalingGroupRequest.setAutoScalingGroupName(autoScalingGroup.getAutoScalingGroupName)
            updateAutoScalingGroupRequest.setDesiredCapacity((autoScalingGroup.getDesiredCapacity - replacementInfo.instanceCount).max(0)) //This shields against negative numbers
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

    private[this] def composeNewLaunchConfigurationRequest(instanceType: InstanceType, replacementInfo: ReplacementInfo, launchConfiguration: LaunchConfiguration): CreateLaunchConfigurationRequest =
    {
        val createLaunchConfigurationRequest: CreateLaunchConfigurationRequest = new CreateLaunchConfigurationRequest
        createLaunchConfigurationRequest.setImageId(launchConfiguration.getImageId)
        createLaunchConfigurationRequest.setKeyName(launchConfiguration.getKeyName)
        createLaunchConfigurationRequest.setSecurityGroups(launchConfiguration.getSecurityGroups)
        createLaunchConfigurationRequest.setUserData(launchConfiguration.getUserData)
        createLaunchConfigurationRequest.setInstanceType(s"$instanceType")
        createLaunchConfigurationRequest.setSpotPrice(replacementInfo.getTagValue(SpotPriceTag))
        createLaunchConfigurationRequest.setLaunchConfigurationName(getLaunchConfigurationNameWithInstanceType(replacementInfo.baseLaunchConfigurationName, s"$instanceType"))
        createLaunchConfigurationRequest
    }
}