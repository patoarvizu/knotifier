knotifier
=========

This service monitors the Spot instance prices and auto-scaling groups. It keeps track of prices per instance type and availability zone and dynamically modifies the auto-scaling groups when it detects that there's a cheaper instance type and/or in a differente availability zone.
