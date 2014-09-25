package actors

import java.util.ArrayList
import java.util.Map.Entry
import scala.collection.JavaConversions._
import scala.collection.Map
import scala.collection.SortedMap
import scala.collection.mutable.HashMap
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import com.amazonaws.services.autoscaling.model._
import com.amazonaws.services.ec2.model.InstanceType
import com.amazonaws.services.sqs.model._
import com.amazonaws.util.json.JSONObject
import akka.actor.ActorRef
import akka.actor.TypedActor
import akka.actor.TypedProps
import akka.util.Timeout
import model.ReplacementInfo
import model.SpotPriceInfo
import play.Logger
import play.libs.Akka
import scala.concurrent.duration.FiniteDuration

class AutoScaleModifierImpl extends AutoScaleModifier {
    
	private final val SPOT_GROUP_TYPE: String = "Spot";
    private final val ON_DEMAND_GROUP_TYPE: String = "OnDemand";
    private final val AUTOSCALING_INSTANCE_TERMINATE_MESSAGE: String = "autoscaling:EC2_INSTANCE_TERMINATE";
    private final val SPOT_PRICE_TAG: String = "SpotPrice";
    private final val GROUP_TYPE_TAG: String = "GroupType";
    private final val SPOT_GROUP_NAME_SUFFIX: String = "-spot";
    private final val TIMEOUT_DURATION: FiniteDuration = 5.seconds;
    private implicit val timeout: Timeout = new Timeout(TIMEOUT_DURATION)
    private val spotReplacementInfoByGroup: HashMap[String, ReplacementInfo] = new HashMap[String, ReplacementInfo]();
    
	val priceMonitor: PriceMonitor = {
    	val priceMonitorActorRefFuture: Future[ActorRef] = Akka.system.actorSelection("akka://application/user/priceMonitor").resolveOne;
        priceMonitorActorRefFuture.onComplete({
            case Failure(e) => throw e;
            case Success(priceMonitorActorRef) => priceMonitorActorRef;
        })
        TypedActor.get(typedActorContext).typedActorOf(new TypedProps[PriceMonitorImpl](classOf[PriceMonitor], classOf[PriceMonitorImpl]), Await.result(priceMonitorActorRefFuture, TIMEOUT_DURATION));
    };
    
    val autoScalingDataMonitor: AutoScalingDataMonitor = {
        val autoScalingDataMonitorFuture: Future[ActorRef] = Akka.system.actorSelection("akka://application/user/autoScalingDataMonitor").resolveOne;
        autoScalingDataMonitorFuture.onComplete({
            case Failure(e) => throw e;
            case Success(autoScalingDataMonitorRef) => autoScalingDataMonitorRef;
        });
        TypedActor.get(typedActorContext).typedActorOf(new TypedProps[AutoScalingDataMonitorImpl](classOf[AutoScalingDataMonitor], classOf[AutoScalingDataMonitorImpl]), Await.result(autoScalingDataMonitorFuture, TIMEOUT_DURATION));
    };
	
	def monitorAutoScaleGroups(): Unit = {
		implicit val autoScalingGroups: Map[String, AutoScalingGroup] = autoScalingDataMonitor.getAutoScaleData();
		implicit val launchConfigurations: Map[String, LaunchConfiguration] = autoScalingDataMonitor.getLaunchConfigurationData();
		if(autoScalingGroups.isEmpty || launchConfigurations.isEmpty)
		{
			Logger.debug("AutoScaling data is not ready yet");
			return;
		}
		val queueResult: CreateQueueResult = sqsClient.createQueue("load-balancer-test");
		val sqsMessages: ReceiveMessageResult = sqsClient.receiveMessage(new ReceiveMessageRequest().withQueueUrl(queueResult.getQueueUrl()));
		sqsMessages.getMessages() map {sqsMessage => processSQSMessage(sqsMessage)};
		spotReplacementInfoByGroup.keySet map { group => processReplacementInfo(group) }
		sqsMessages.getMessages() map { sqsMessage => sqsClient.deleteMessage(queueResult.getQueueUrl(), sqsMessage.getReceiptHandle());};
		autoScalingDataMonitor.updateAutoScalingGroupsData();
        spotReplacementInfoByGroup.clear();
	};

	private[this] def processSQSMessage(sqsMessage: Message)(implicit autoScalingGroups: Map[String, AutoScalingGroup]): Unit = {
        val message: JSONObject = new JSONObject(sqsMessage.getBody());
            val notification: JSONObject = new JSONObject(message.getString("Message"));
            val autoScalingGroupName: String = notification.getString("AutoScalingGroupName");
            val autoScalingGroup: AutoScalingGroup = autoScalingGroups.getOrElse(autoScalingGroupName, throw new Exception("Auto-scaling group " + autoScalingGroupName + " doesn't exist"));
            val tags: Map[String, String] = getTagMap(autoScalingGroup.getTags().toList);
            tags.keys foreach { tagKey => Logger.debug(tagKey); };
            val groupType: String = tags.getOrElse("GroupType", null);
            if(AUTOSCALING_INSTANCE_TERMINATE_MESSAGE equals(notification.getString("Event")))
            {
                if(ON_DEMAND_GROUP_TYPE equals(groupType))
                    if(spotReplacementInfoByGroup.containsKey(autoScalingGroupName))
                        spotReplacementInfoByGroup.getOrElse(autoScalingGroupName, null).increaseInstanceCount;
                    else
                        spotReplacementInfoByGroup.put(autoScalingGroupName, new ReplacementInfo(autoScalingGroup.getLaunchConfigurationName(), autoScalingGroup.getDesiredCapacity(), tags));
            }
    }

	private[this] def processReplacementInfo(group: String)(implicit autoScalingGroups: Map[String, AutoScalingGroup], launchConfigurations: Map[String, LaunchConfiguration]): Unit = {
		val replacementInfo: ReplacementInfo = spotReplacementInfoByGroup.getOrElse(group, throw new Exception("Replacement info object doesn't exist"));
        Logger.debug("Replacements needed for group " + group + ": " + replacementInfo.newInstances);
        Logger.debug("Original capacity for group " + group + ": " + replacementInfo.originalCapacity);
        val newInstanceType: String = discoverNewInstanceType(replacementInfo.getTagValue("PreferredTypes"));
        launchConfigurations synchronized {
            if(!launchConfigurations.containsKey(replacementInfo.launchConfigurationName + "-" + newInstanceType))
            {
                val createLaunchConfigurationRequest: CreateLaunchConfigurationRequest = composeNewLaunchConfigurationRequest(launchConfigurations.getOrElse(replacementInfo.launchConfigurationName, null), newInstanceType, replacementInfo.tags.getOrElse(SPOT_PRICE_TAG, ""));
                asClient.createLaunchConfiguration(createLaunchConfigurationRequest);
                autoScalingDataMonitor.updateLaunchConfigurationsData();
            }
        }
        autoScalingGroups synchronized
        {
            if(autoScalingGroups.containsKey(group + SPOT_GROUP_NAME_SUFFIX))
            {
                Logger.debug("Update existing auto scaling group");
                asClient.updateAutoScalingGroup(new UpdateAutoScalingGroupRequest()
                .withAutoScalingGroupName(
                        group + SPOT_GROUP_NAME_SUFFIX)
                        .withLaunchConfigurationName(
                                replacementInfo.launchConfigurationName + "-" + newInstanceType)
                                .withDesiredCapacity(
                                        autoScalingGroups.getOrElse(group + SPOT_GROUP_NAME_SUFFIX, null)
                                        .getDesiredCapacity()
                                        + replacementInfo.newInstances));
            }
            else
            {
                Logger.debug("Create new auto scaling group");
                asClient.createAutoScalingGroup(composeNewAutoScalingGroupRequest(group, newInstanceType, replacementInfo, autoScalingGroups));
            }
        }
	}

	private[this] def getTagMap(tagDescriptions: List[TagDescription]): Map[String, String] = {
		tagDescriptions map { tagDescription => tagDescription.getKey() -> tagDescription.getValue()} toMap
	}
	
	private[this] def discoverNewInstanceType(preferredTypes: String): String =
    {
        val preferredTypesList: List[String] = preferredTypes.split(",").toList;
        val lowestPrices: HashMap[InstanceType, SpotPriceInfo] = new HashMap[InstanceType, SpotPriceInfo]();
        if(priceMonitor != null)
            lowestPrices ++= priceMonitor.getPrices();
        val sortedPrices: SortedMap[String, InstanceType] =
        	SortedMap(lowestPrices.entrySet().collect({
        	case spotInstancePriceEntry: Entry[InstanceType, SpotPriceInfo]
        	        if (preferredTypesList.contains(spotInstancePriceEntry.getKey().toString())) =>
        	        	(spotInstancePriceEntry.getValue().instanceType.toString() -> spotInstancePriceEntry.getKey());}).toSeq:_*);
        sortedPrices.head.toString();
    }
	
	private[this] def composeNewAutoScalingGroupRequest(group: String, newInstanceType: String, replacementInfo: ReplacementInfo, autoScalingGroups: Map[String, AutoScalingGroup]): CreateAutoScalingGroupRequest =
    {
        val autoScalingGroup: AutoScalingGroup = autoScalingGroups.getOrElse(group, null);
        val createAutoScalingGroupRequest: CreateAutoScalingGroupRequest = new CreateAutoScalingGroupRequest();
        createAutoScalingGroupRequest.setAutoScalingGroupName(group + SPOT_GROUP_NAME_SUFFIX);
        createAutoScalingGroupRequest.setAvailabilityZones(autoScalingGroup.getAvailabilityZones());
        createAutoScalingGroupRequest.setDefaultCooldown(0);
        createAutoScalingGroupRequest.setDesiredCapacity(replacementInfo.newInstances);
        createAutoScalingGroupRequest.setHealthCheckGracePeriod(autoScalingGroup.getHealthCheckGracePeriod());
        createAutoScalingGroupRequest.setHealthCheckType(autoScalingGroup.getHealthCheckType());
        createAutoScalingGroupRequest.setLaunchConfigurationName(newInstanceTypeLaunchConfigurationName(replacementInfo.launchConfigurationName, newInstanceType));
        createAutoScalingGroupRequest.setLoadBalancerNames(autoScalingGroup.getLoadBalancerNames());
        createAutoScalingGroupRequest.setMaxSize(autoScalingGroup.getMaxSize());
        createAutoScalingGroupRequest.setMinSize(autoScalingGroup.getMinSize());
        val tags: ArrayList[Tag] = new ArrayList[Tag]();
        tags.add(new Tag().withKey("GroupType").withValue(SPOT_GROUP_TYPE));
        tags.add(new Tag().withKey("Name").withValue(createAutoScalingGroupRequest.getAutoScalingGroupName()));
        createAutoScalingGroupRequest.setTags(tags);
        createAutoScalingGroupRequest;
    }
	
	private[this] def composeNewLaunchConfigurationRequest(launchConfiguration: LaunchConfiguration, instanceType: String, spotPrice: String): CreateLaunchConfigurationRequest =
    {
        val createLaunchConfigurationRequest: CreateLaunchConfigurationRequest = new CreateLaunchConfigurationRequest();
        createLaunchConfigurationRequest.setImageId(launchConfiguration.getImageId());
        createLaunchConfigurationRequest.setKeyName(launchConfiguration.getKeyName());
        createLaunchConfigurationRequest.setSecurityGroups(launchConfiguration.getSecurityGroups());
        createLaunchConfigurationRequest.setUserData(launchConfiguration.getUserData());
        createLaunchConfigurationRequest.setInstanceType(instanceType);
        createLaunchConfigurationRequest.setSpotPrice(spotPrice);
        createLaunchConfigurationRequest.setLaunchConfigurationName(newInstanceTypeLaunchConfigurationName(launchConfiguration.getLaunchConfigurationName(), instanceType));
        createLaunchConfigurationRequest;
    }
	
	private[this] def newInstanceTypeLaunchConfigurationName(launchConfigurationName: String, instanceType: String): String =
    {
        return launchConfigurationName + "-" + instanceType;
    }
}

