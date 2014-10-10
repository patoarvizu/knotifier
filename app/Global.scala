import play.api.GlobalSettings
import play.api.Application
import actors.AutoScaleModifier
import play.libs.Akka
import actors.AutoScalingDataMonitor
import actors.PriceMonitor
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import play.Logger

object Global extends GlobalSettings {

    override def onStart(app: Application) {
        Akka.system.scheduler.schedule(0.seconds, 10.seconds) {
            wrapExceptionHandling(AutoScaleModifier.monitorAutoScaleGroups)
        }
        Akka.system.scheduler.schedule(0.seconds, 60.seconds) {
            wrapExceptionHandling(PriceMonitor.monitorSpotPrices)
        }
        Akka.system.scheduler.schedule(0.seconds, 15.seconds) {
            wrapExceptionHandling(PriceMonitor.printPrices)
        }
        Akka.system.scheduler.schedule(0.seconds, 60.seconds) {
            wrapExceptionHandling(AutoScalingDataMonitor.monitorAutoScalingData)
        }
    }

    private[this] def wrapExceptionHandling(action: => Unit) = {
        try {
            action
        }
        catch {
            case t:Throwable => {
                Logger.error("Error running scheduled task", t)
                throw t
            }
        }
    }
}