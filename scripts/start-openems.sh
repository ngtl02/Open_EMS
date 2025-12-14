#!/bin/bash
#
# OpenEMS Edge Startup Script for Linux
# This script ensures configuration persistence between restarts
#

# ============================================
# Configuration - Modify these paths as needed
# ============================================
OPENEMS_HOME="/opt/openems"
CONFIG_DIR="$OPENEMS_HOME/config"
DATA_DIR="$OPENEMS_HOME/data"
LOG_DIR="$OPENEMS_HOME/log"
JAR_FILE="$OPENEMS_HOME/openems.jar"

# HTTP Port for Web UI and REST API
HTTP_PORT=8080

# ============================================
# Setup
# ============================================

# Create directories if they don't exist
mkdir -p "$CONFIG_DIR"
mkdir -p "$DATA_DIR"
mkdir -p "$LOG_DIR"

# Check if JAR file exists
if [ ! -f "$JAR_FILE" ]; then
    echo "ERROR: JAR file not found at: $JAR_FILE"
    echo "Please copy the OpenEMS JAR file to $OPENEMS_HOME"
    exit 1
fi

# ============================================
# Start OpenEMS
# ============================================
echo "============================================"
echo "Starting OpenEMS Edge"
echo "============================================"
echo "Config directory: $CONFIG_DIR"
echo "Data directory:   $DATA_DIR"
echo "Log directory:    $LOG_DIR"
echo "HTTP Port:        $HTTP_PORT"
echo "============================================"

java \
    -Dfelix.cm.dir="$CONFIG_DIR" \
    -Dopenems.data.dir="$DATA_DIR" \
    -Dorg.osgi.service.http.port=$HTTP_PORT \
    -Dorg.apache.felix.eventadmin.Timeout=0 \
    -Dorg.ops4j.pax.logging.DefaultServiceLog.level=INFO \
    -jar "$JAR_FILE"
