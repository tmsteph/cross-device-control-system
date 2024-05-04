# Cross-Device Control System

**Cross-Device Control System** is an open-source project that enables bi-directional communication between a computer and a mobile device. The system allows for mouse and keyboard input as well as screen sharing across devices, and even supports VR control of multiple computers.

## Table of Contents

1. [Features](#features)
2. [Project Structure](#project-structure)
3. [Setup](#setup)
4. [Usage](#usage)
5. [Contributing](#contributing)
6. [License](#license)
7. [Contact](#contact)

## Features

- **Bi-Directional Input**: Control a computer using a mobile device, and vice versa.
- **Screen Sharing**: Stream the screen from a computer to a mobile device or the other way around.
- **Wireless Control**: Use your mobile device as a wireless mouse, keyboard, and remote monitor.
- **VR Control**: Manage multiple computers remotely using VR.

## Project Structure

```plaintext
Cross-Device-Control-System/
├── mobile-app/
│   ├── lib/
│   ├── android/
│   ├── ios/
│   ├── pubspec.yaml
│   ├── README.md
│   └── ...
├── desktop-app/
│   ├── src/
│   ├── public/
│   ├── package.json
│   ├── README.md
│   └── ...
├── vr-app/
│   ├── Assets/
│   ├── ProjectSettings/
│   ├── README.md
│   └── ...
├── README.md
└── LICENSE
```

## Setup

### Prerequisites

Ensure you have the following software installed:

- **Flutter** for the mobile application.
- **Node.js** for the desktop application.
- **Unity** for the VR application.

### Mobile App

1. Navigate to the `mobile-app` directory.
2. Install dependencies:

   ```bash
   flutter pub get
   ```

3. Run the app:

   ```bash
   flutter run
   ```

### Desktop App

1. Navigate to the `desktop-app` directory.
2. Install dependencies:

   ```bash
   npm install
   ```

3. Run the app:

   ```bash
   npm start
   ```

### VR App

1. Open the `vr-app` directory in Unity.
2. Ensure your VR headset is connected.
3. Run the app from Unity.

## Usage

### Bi-Directional Input

1. Launch the mobile and desktop applications.
2. Establish a connection using the provided connection code.
3. Use the mobile device as a wireless mouse, keyboard, or remote monitor.

### Screen Sharing

1. Select the screen sharing option on either the mobile or desktop app.
2. Choose the direction (e.g., from computer to phone or vice versa).
3. Enjoy seamless screen sharing.

### VR Control

1. Launch the VR app.
2. Select a computer to control.
3. Use the VR controllers for mouse and keyboard input.

## Contributing

Contributions are welcome! Please follow these steps to contribute:

1. **Fork** the repository.
2. **Create** a new branch (`git checkout -b feature-branch-name`).
3. **Commit** your changes (`git commit -am 'Add new feature'`).
4. **Push** to the branch (`git push origin feature-branch-name`).
5. **Submit** a pull request.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Contact

For any inquiries or feedback, please contact [Your Name](mailto:your.email@example.com).

---

Feel free to customize this `README.md` file further based on your project's evolving requirements and features.
