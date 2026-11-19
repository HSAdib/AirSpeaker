//
//  AirSpeakerApp.swift
//  AirSpeaker
//
//  Created for Air Speaker v2.1.
//

import SwiftUI
import UIKit

@main
struct AirSpeakerApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
                .onAppear {
                    // Keep screen awake while studio is active
                    UIApplication.shared.isIdleTimerDisabled = true
                }
        }
    }
}


// Backward compatibility alias
typealias PhoneSpeakerApp = AirSpeakerApp
