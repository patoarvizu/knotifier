package controllers

import play.api.mvc.Action
import play.api.mvc.Controller
import actors.PriceMonitor

object Prices extends Controller {

    def getPrices = Action {
        Ok(new PriceMonitor().getPricesInText)
    }
}