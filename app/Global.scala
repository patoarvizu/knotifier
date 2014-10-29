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
import util.NameHelper

object Global extends GlobalSettings {

    override def onStart(app: Application) {
        val priceMonitor = new PriceMonitor()
        val autoScalingDataMonitor = new AutoScalingDataMonitor()
        val autoScaleModifier = new AutoScaleModifier(autoScalingDataMonitor, priceMonitor)
        Akka.system.scheduler.schedule(0.seconds, 10.seconds) {
            wrapExceptionHandling(autoScaleModifier.monitorAutoScaleGroups)
        }
        Akka.system.scheduler.schedule(0.seconds, 60.seconds) {
            wrapExceptionHandling(priceMonitor.monitorSpotPrices)
        }
        Akka.system.scheduler.schedule(0.seconds, 15.seconds) {
            wrapExceptionHandling(priceMonitor.printPrices)
        }
        Akka.system.scheduler.schedule(0.seconds, 60.seconds) {
            wrapExceptionHandling(autoScalingDataMonitor.monitorAutoScalingData)
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