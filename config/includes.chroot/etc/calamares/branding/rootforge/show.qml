/* RootForge OS — Calamares installer slideshow
 * Victorious Framework | Origin Source Labs
 *
 * Displayed in the right panel while the installer copies files.
 * Simple single-slide layout — extend with more Slide {} blocks as needed.
 */

import QtQuick 2.0
import calamares.slideshow 1.0

Presentation {
    id: presentation

    Timer {
        interval: 20000
        running: presentation.activatedInCalamares
        repeat: true
        onTriggered: presentation.goToNextSlide()
    }

    Slide {
        anchors.fill: parent

        Rectangle {
            anchors.fill: parent
            color: "#0d0d0d"
        }

        Column {
            anchors.centerIn: parent
            spacing: 20

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "RootForge OS"
                color: "#00ff41"
                font.pixelSize: 36
                font.bold: true
                font.family: "JetBrains Mono"
            }

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "Android Root Module Development Platform"
                color: "#aaaaaa"
                font.pixelSize: 16
            }

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "Victorious Framework  |  Origin Source Labs"
                color: "#666666"
                font.pixelSize: 12
            }
        }
    }

    Slide {
        anchors.fill: parent

        Rectangle {
            anchors.fill: parent
            color: "#0d0d0d"
        }

        Column {
            anchors.centerIn: parent
            spacing: 16
            width: parent.width * 0.8

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "What's included"
                color: "#00ff41"
                font.pixelSize: 24
                font.bold: true
            }

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "Magisk · KernelSU · APatch  |  magiskboot · avbtool · repo\n" +
                      "ADB/Fastboot · KVM emulator acceleration\n" +
                      "Claude Code · Ollama · Grok  |  WireGuard · mitmproxy\n" +
                      "27 automation scripts — all in /usr/local/bin"
                color: "#aaaaaa"
                font.pixelSize: 13
                horizontalAlignment: Text.AlignHCenter
            }
        }
    }

    Slide {
        anchors.fill: parent

        Rectangle {
            anchors.fill: parent
            color: "#0d0d0d"
        }

        Column {
            anchors.centerIn: parent
            spacing: 16

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "First boot"
                color: "#00ff41"
                font.pixelSize: 24
                font.bold: true
            }

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "After reboot, the first-boot service will automatically\n" +
                      "provision the Android SDK, NDK, and emulator images.\n\n" +
                      "Network access required — allow ~20 minutes."
                color: "#aaaaaa"
                font.pixelSize: 13
                horizontalAlignment: Text.AlignHCenter
            }
        }
    }
}
