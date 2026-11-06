import SwiftUI
import TvKit

// Tv för iPad. Själva appen är skriven i Kotlin och ritas med Compose, samma
// sätt som Windows och Shield; Swift visar den bara på skärmen.
@main
struct TvIpadApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
