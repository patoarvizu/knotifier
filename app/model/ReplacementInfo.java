package model;

import java.util.Map;

import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.LaunchConfiguration;

public class ReplacementInfo
{
    public String launchConfigurationName;
    public LaunchConfiguration launchConfiguration;
    public AutoScalingGroup autoScalingGroup;
    public int newInstances = 1;
    public int originalCapacity;
    public Map<String, String> tags;

    public ReplacementInfo(String launchConfigurationName, Integer originalCapacity)
    {
        this.launchConfigurationName = launchConfigurationName;
        this.originalCapacity = originalCapacity;
    }
    
    public ReplacementInfo withTags(Map<String, String> tags)
    {
        this.tags = tags;
        return this;
    }
    
    public ReplacementInfo withLaunchConfigurationName(String launchConfigurationName)
    {
        this.launchConfigurationName = launchConfigurationName;
        return this;
    }

    public ReplacementInfo withOriginalCapatity(Integer originalCapacity)
    {
        this.originalCapacity = originalCapacity;
        return this;
    }

    public ReplacementInfo withLaunchConfiguration(LaunchConfiguration launchConfiguration)
    {
        this.launchConfiguration = launchConfiguration;
        return this;
    }

    public ReplacementInfo withAutoScalingGroup(AutoScalingGroup autoScalingGroup)
    {
        this.autoScalingGroup = autoScalingGroup;
        return this;
    }

    public void increaseInstanceCount()
    {
        newInstances++;
    }
    
    public String getTagValue(String key)
    {
        return tags.get(key);
    }
}