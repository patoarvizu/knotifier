package actors;

public interface AutoScaleModifier extends AmazonClientActor
{
    public void monitorAutoScaleGroups() throws Exception;
}
