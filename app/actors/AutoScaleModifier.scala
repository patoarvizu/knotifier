package actors

trait AutoScaleModifier extends AmazonClientActor {
    def monitorAutoScaleGroups(): Unit
}