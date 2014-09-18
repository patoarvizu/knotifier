package actors;

import java.util.Map;

import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.LaunchConfiguration;

public interface AutoScalingDataMonitor extends AmazonClientActor
{
    public void monitorAutoScalingData();
    
    public void updateAutoScalingGroupsData();
    
    public void updateLaunchConfigurationsData();
    
    public Map<String, AutoScalingGroup> getAutoScaleData();
    
    public Map<String, LaunchConfiguration> getLaunchConfigurationData();
}
