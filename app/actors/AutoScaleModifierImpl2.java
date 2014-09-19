package actors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import play.Logger;
import play.libs.Akka;
import scala.concurrent.Future;
import model.ReplacementInfo;
import akka.actor.ActorRef;
import akka.actor.TypedActor;
import akka.actor.TypedProps;
import akka.dispatch.OnComplete;
import akka.util.Timeout;

import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.CreateAutoScalingGroupRequest;
import com.amazonaws.services.autoscaling.model.CreateLaunchConfigurationRequest;
import com.amazonaws.services.autoscaling.model.LaunchConfiguration;
import com.amazonaws.services.autoscaling.model.Tag;
import com.amazonaws.services.autoscaling.model.TagDescription;
import com.amazonaws.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.SpotPrice;
import com.amazonaws.services.sqs.model.CreateQueueResult;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.amazonaws.util.json.JSONObject;

public class AutoScaleModifierImpl2 implements AutoScaleModifier
{
    private static final String SPOT_GROUP_TYPE = "Spot";
    private static final String ON_DEMAND_GROUP_TYPE = "OnDemand";
    private static final String AUTOSCALING_INSTANCE_TERMINATE_MESSAGE = "autoscaling:EC2_INSTANCE_TERMINATE";
    private static Timeout TIMEOUT = new Timeout(5, TimeUnit.SECONDS);
    private PriceMonitor priceMonitor;
    private AutoScalingDataMonitor autoScalingDataMonitor;
    private HashMap<String, ReplacementInfo> spotReplacementInfoByGroup = new HashMap<String, ReplacementInfo>();
    private Map<String, AutoScalingGroup> autoScalingGroups;
    private Map<String, LaunchConfiguration> launchConfigurations;

    public AutoScaleModifierImpl2()
    {
        Future<ActorRef> priceMonitorActorRefFuture = Akka.system()
                .actorSelection("akka://application/user/priceMonitor")
                .resolveOne(TIMEOUT);
        priceMonitorActorRefFuture.onComplete(new OnComplete<ActorRef>()
        {
            @Override
            public void onComplete(Throwable e, ActorRef priceMonitorActorRef)
                    throws Throwable
            {
                if (e != null) throw e;
                priceMonitor = TypedActor.get(typedActorContext).typedActorOf(
                        new TypedProps<PriceMonitorImpl>(PriceMonitor.class,
                                PriceMonitorImpl.class), priceMonitorActorRef);
            }
        }, Akka.system().dispatcher());
        Future<ActorRef> autoScalingDataMonitorFuture = Akka.system()
                .actorSelection("akka://application/user/autoScalingDataMonitor")
                .resolveOne(TIMEOUT);
        autoScalingDataMonitorFuture.onComplete(new OnComplete<ActorRef>()
        {

            @Override
            public void onComplete(Throwable e, ActorRef autoScalingDataMonitorRef)
                    throws Throwable
            {
                if(e != null) throw e;
                autoScalingDataMonitor = TypedActor.get(typedActorContext)
                        .typedActorOf(
                                new TypedProps<AutoScalingDataMonitorImpl>(
                                        AutoScalingDataMonitor.class,
                                        AutoScalingDataMonitorImpl.class),
                                autoScalingDataMonitorRef);
            }
    
        }, Akka.system().dispatcher());
    }

    @Override
    public void monitorAutoScaleGroups() throws Exception
    {
        autoScalingGroups = autoScalingDataMonitor.getAutoScaleData();
        launchConfigurations = autoScalingDataMonitor.getLaunchConfigurationData();
        if(autoScalingGroups.isEmpty() || launchConfigurations.isEmpty())
        {
            Logger.debug("AutoScaling data is not ready yet");
            return;
        }
        CreateQueueResult createQueueResult = sqsClient.createQueue("load-balancer-test");
        ReceiveMessageResult sqsMessages = sqsClient.receiveMessage(new ReceiveMessageRequest().withMaxNumberOfMessages(10).withQueueUrl(createQueueResult.getQueueUrl()));
        for(Message sqsMessage : sqsMessages.getMessages())
        {
            JSONObject message = new JSONObject(sqsMessage.getBody());
            JSONObject notification = new JSONObject(message.getString("Message"));
            String autoScalingGroupName = notification.getString("AutoScalingGroupName");
            AutoScalingGroup autoScalingGroup = autoScalingGroups.get(autoScalingGroupName);
            HashMap<String, String> tags = getTagMap(autoScalingGroup.getTags());
            String groupType = tags.get("GroupType");
            if(AUTOSCALING_INSTANCE_TERMINATE_MESSAGE.equals(notification.getString("Event")))
            {
                if(ON_DEMAND_GROUP_TYPE.equals(groupType))
                    if(spotReplacementInfoByGroup.containsKey(autoScalingGroupName))
                        spotReplacementInfoByGroup.get(autoScalingGroupName).increaseInstanceCount();
                    else
                        spotReplacementInfoByGroup.put(autoScalingGroupName, new ReplacementInfo(autoScalingGroup.getLaunchConfigurationName(), autoScalingGroup.getDesiredCapacity()).withTags(tags));
            }
            sqsClient.deleteMessage(createQueueResult.getQueueUrl(), sqsMessage.getReceiptHandle());
        }
        for(String group : spotReplacementInfoByGroup.keySet())
        {
            ReplacementInfo replacementInfo = spotReplacementInfoByGroup.get(group);
            Logger.debug("Replacements needed for group " + group + ": " + replacementInfo.newInstances);
            Logger.debug("Original capacity for group " + group + ": " + replacementInfo.originalCapacity);
            String newInstanceType = discoverNewInstanceType(replacementInfo.getTagValue("PreferredTypes"));
            synchronized(launchConfigurations)
            {
                if(!launchConfigurations.containsKey(replacementInfo.launchConfigurationName + "-" + newInstanceType))
                {
                    // Create new launch configuration
                    Logger.debug("Create new launch configuration");
                    CreateLaunchConfigurationRequest createLaunchConfigurationRequest = composeNewLaunchConfigurationRequest(launchConfigurations.get(replacementInfo.launchConfigurationName), newInstanceType, replacementInfo.tags.get("SpotPrice"));
                    asClient.createLaunchConfiguration(createLaunchConfigurationRequest);
                    autoScalingDataMonitor.updateLaunchConfigurationsData();
                }
            }
            synchronized(autoScalingGroups)
            {
                if(!autoScalingGroups.containsKey(group + "-spot"))
                {
                    //Create new auto scaling group
                    Logger.debug("Create new auto scaling group");
                    asClient.createAutoScalingGroup(composeNewAutoScalingGroupRequest(group, newInstanceType, replacementInfo));
                }
                else
                {
                    Logger.debug("Update existing auto scaling group");
                    asClient.updateAutoScalingGroup(new UpdateAutoScalingGroupRequest()
                            .withAutoScalingGroupName(
                                    group + "-spot")
                            .withLaunchConfigurationName(
                                    replacementInfo.launchConfigurationName + "-" + newInstanceType)
                            .withDesiredCapacity(
                                    autoScalingGroups.get(group + "-spot")
                                            .getDesiredCapacity()
                                            + replacementInfo.newInstances));
                    
                }
            }
        }
        autoScalingDataMonitor.updateAutoScalingGroupsData();
        spotReplacementInfoByGroup.clear();
    }
    
    private CreateAutoScalingGroupRequest composeNewAutoScalingGroupRequest(String group, String newInstanceType, ReplacementInfo replacementInfo)
    {
        AutoScalingGroup autoScalingGroup = autoScalingGroups.get(group);
        CreateAutoScalingGroupRequest createAutoScalingGroupRequest = new CreateAutoScalingGroupRequest();
        createAutoScalingGroupRequest.setAutoScalingGroupName(group + "-spot");
        createAutoScalingGroupRequest.setAvailabilityZones(autoScalingGroup.getAvailabilityZones());
        createAutoScalingGroupRequest.setDefaultCooldown(0);
        createAutoScalingGroupRequest.setDesiredCapacity(replacementInfo.newInstances);
        createAutoScalingGroupRequest.setHealthCheckGracePeriod(autoScalingGroup.getHealthCheckGracePeriod());
        createAutoScalingGroupRequest.setHealthCheckType(autoScalingGroup.getHealthCheckType());
        createAutoScalingGroupRequest.setLaunchConfigurationName(newInstanceTypeLaunchConfigurationName(replacementInfo.launchConfigurationName, newInstanceType));
        createAutoScalingGroupRequest.setLoadBalancerNames(autoScalingGroup.getLoadBalancerNames());
        createAutoScalingGroupRequest.setMaxSize(autoScalingGroup.getMaxSize());
        createAutoScalingGroupRequest.setMinSize(autoScalingGroup.getMinSize());
        ArrayList<Tag> tags = new ArrayList<Tag>();
        tags.add(new Tag().withKey("GroupType").withValue(SPOT_GROUP_TYPE));
        tags.add(new Tag().withKey("Name").withValue(createAutoScalingGroupRequest.getAutoScalingGroupName()));
        createAutoScalingGroupRequest.setTags(tags);
        return createAutoScalingGroupRequest;
    }
    
    private CreateLaunchConfigurationRequest composeNewLaunchConfigurationRequest(LaunchConfiguration launchConfiguration, String instanceType, String spotPrice)
    {
        CreateLaunchConfigurationRequest createLaunchConfigurationRequest = new CreateLaunchConfigurationRequest();
        createLaunchConfigurationRequest.setImageId(launchConfiguration.getImageId());
        createLaunchConfigurationRequest.setKeyName(launchConfiguration.getKeyName());
        createLaunchConfigurationRequest.setSecurityGroups(launchConfiguration.getSecurityGroups());
        createLaunchConfigurationRequest.setUserData(launchConfiguration.getUserData());
        createLaunchConfigurationRequest.setInstanceType(instanceType);
        createLaunchConfigurationRequest.setSpotPrice(spotPrice);
        createLaunchConfigurationRequest.setLaunchConfigurationName(newInstanceTypeLaunchConfigurationName(launchConfiguration.getLaunchConfigurationName(), instanceType));
        return createLaunchConfigurationRequest;
    }

    private String newInstanceTypeLaunchConfigurationName(String launchConfigurationName, String instanceType)
    {
        return launchConfigurationName + "-" + instanceType;
    }

    private String discoverNewInstanceType(String preferredTypes)
    {
        List<String> preferredTypesList = Arrays.asList(preferredTypes.split(","));
        HashMap<InstanceType, SpotPrice> lowestPrices = new HashMap<InstanceType, SpotPrice>();
        if(priceMonitor != null)
            lowestPrices.putAll(priceMonitor.getPrices());
        TreeMap<String, InstanceType> sortedPrices = new TreeMap<String, InstanceType>();
        for(Entry<InstanceType, SpotPrice> spotInstancePriceEntry : lowestPrices.entrySet())
            if(preferredTypesList.contains(spotInstancePriceEntry.getKey().toString()))
                sortedPrices.put(spotInstancePriceEntry.getValue().getSpotPrice(), spotInstancePriceEntry.getKey());
        Logger.debug("Cheapest instance: " + sortedPrices.get(sortedPrices.firstKey()).toString());
        return sortedPrices.get(sortedPrices.firstKey()).toString();
    }

    private HashMap<String, String> getTagMap(List<TagDescription> tagDescriptions)
    {
        HashMap<String, String> tags = new HashMap<String, String>();
        for(TagDescription tagDescription : tagDescriptions)
        {
            tags.put(tagDescription.getKey(), tagDescription.getValue());
        }
        return tags;
    }
}