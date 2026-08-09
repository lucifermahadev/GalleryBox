# GalleryBox - Music & Video Editor

GalleryBox is a modern Android multimedia application built with **Kotlin and Jetpack Compose**.

It brings photos, videos, albums, stories, music playback, a Duo Music Player, radio, and media management features together in one application.

The project is designed with a strong focus on **local/offline media management**, a clean user interface, and a Samsung Gallery-inspired experience.

---

## 📱 Features

### 🖼️ Photos

GalleryBox provides a powerful photo gallery for browsing and managing images stored on your device.

Features include:

- Browse all photos
- Grid-based photo layout
- Adjustable grid size
- Full-screen photo viewer
- Pinch-to-zoom
- Search photos
- Favorite photos
- Photo selection
- Multi-select operations
- Slideshow
- Sort and organize media
- Recent media
- Album-based organization
- Smart album categories

GalleryBox is designed to make browsing large photo collections fast and simple.

---

## 🎬 Videos

GalleryBox also provides a dedicated video experience.

Features include:

- Browse videos stored on the device
- Video thumbnails
- Full-screen video playback
- Video seeking
- Play/Pause controls
- Previous/Next navigation
- Video rotation
- Video selection
- Search videos
- Favorite videos
- Album-based video organization
- Recent videos

---

## 📁 Albums

Organize your media into albums and manage them directly from the gallery.

Features include:

- Create albums
- Rename albums
- Delete albums
- Move media
- Copy media
- Merge albums
- Pin albums
- Hide albums
- Album reordering
- Album thumbnails
- Photo/Video filtering inside albums
- Album search
- Smart album categories

### Smart Albums

GalleryBox can organize media into useful categories such as:

- Camera
- Videos
- Screenshots
- Downloads
- WhatsApp media
- Recent
- Favorites

---

## ⭐ Favorites

Quickly access your favorite photos and videos.

The favorite system is synchronized across the gallery and album views so that favorite media remains consistent throughout the application.

---

## 🗑️ Trash

GalleryBox includes a trash/recycle-bin system for deleted media.

Features include:

- Move media to Trash
- Restore deleted media
- Permanently delete media
- Automatic expiration
- 30-day trash lifecycle

This helps prevent accidental permanent deletion.

---

## 🔒 Hidden Media

GalleryBox provides options to hide selected media and albums from the normal gallery experience.

Features include:

- Hide photos
- Hide videos
- Hide albums
- Restore hidden media
- Separate hidden-media management

---

## 📖 Stories

GalleryBox includes a Stories system for creating collections of memories from your media.

Features include:

- Automatically generated stories
- Manual stories
- Add photos to stories
- Add videos to stories
- Story organization
- Story thumbnails
- Story playback
- Story management

The story system can group related media based on factors such as time and other available media information.

---

# 🎵 Music Player

GalleryBox is not only a gallery.

It also includes a complete local music player.

Features include:

- Browse songs stored on the device
- Play/Pause
- Seek bar
- Song progress
- Previous/Next
- Queue
- Shuffle
- Repeat
- Sleep timer
- Album-based music browsing
- Artist information
- Song information
- Mini Player
- Full Player
- Background playback

---

# 🎧 Duo Music Player

One of the major features of GalleryBox is the **Duo Music Player**.

Duo Player allows two music tracks to be managed and played as a dedicated dual-track experience.

Features include:

- Two-track music interface
- Independent track selection
- Track controls
- Playback management
- Music switching
- Dual-track experience

The Duo Player is designed for users who want a different way to experience and manage music playback.

---

# 🎚️ Equalizer

GalleryBox includes audio enhancement features.

Available audio controls can include:

- Equalizer
- Bass Boost
- Virtualizer
- Audio playback controls

These features are designed to provide more control over the listening experience.

---

# 📻 Radio

GalleryBox also includes a Radio experience for listening to available radio streams.

Features include:

- Radio stations
- Station playback
- Play/Pause
- Station selection
- Radio browsing
- Dedicated radio interface

Radio availability depends on the configured stations and network connectivity.

---

# 🎞️ Video Tools

GalleryBox includes video-related tools and playback functionality.

The project uses Android media technologies for handling video playback and media processing.

Features may include:

- Video playback
- Video rotation
- Video processing
- Media selection
- Video export functionality
- Video editing components

---

# 🛠️ Technology Stack

GalleryBox is built using modern Android development technologies.

### Android

- Kotlin
- Jetpack Compose
- Material 3
- Android SDK
- Android MediaStore
- Storage Access Framework
- Room Database
- Kotlin Coroutines
- Android WorkManager

### Media

- Android Media APIs
- Media3 / ExoPlayer
- Media playback components
- Media processing components

### Architecture

The application uses modern Android architecture principles including:

- MVVM
- ViewModel
- Repository pattern
- Room persistence
- Coroutines
- StateFlow / Flow
- Dependency Injection
- Background Workers

---

# 🗄️ Local Storage

GalleryBox is designed around local media management.

The application can work with media stored on the user's device and uses Android storage APIs to access media.

Technologies include:

- MediaStore
- Storage Access Framework
- Room Database

The application does not require users to upload their personal gallery to a cloud service for normal local-gallery functionality.

---

# 💾 SD Card Support

GalleryBox can work with media stored on supported external storage through Android's Storage Access Framework.

This allows users to access and manage media stored outside the primary internal storage when Android permissions allow it.

---

# 🔍 Search

GalleryBox provides search functionality for finding media quickly.

Search can be used across supported:

- Photos
- Videos
- Albums
- Music
- Media collections

---

# 📌 Album Management

GalleryBox provides advanced album organization.

Users can:

- Create albums
- Rename albums
- Move albums
- Reorder albums
- Pin albums
- Hide albums
- Merge albums
- Manage album media

---

# 🧠 Smart Media Organization

GalleryBox uses media information available through Android to organize content into useful categories.

Examples include:

- Camera
- Screenshots
- Downloads
- WhatsApp
- Videos
- Favorites
- Recent media

---

# 🧹 Duplicate Detection

GalleryBox includes duplicate-media detection functionality.

The project can compare media using file information and hashing techniques to help identify duplicate files.

---

# 🗃️ Database

GalleryBox uses **Room Database** for application-managed information.

Database-managed data can include:

- Trash items
- Stories
- Favorites
- Hidden media
- Hidden albums
- Pinned albums
- Album metadata
- Smart tags

Media files themselves remain managed through Android's storage/media systems.

---

# ⚡ Background Processing

GalleryBox uses Android background processing where appropriate.

WorkManager can be used for tasks such as:

- Story generation
- Media processing
- Background synchronization
- Periodic maintenance
- Trash expiration

---

# 🎨 User Interface

GalleryBox is built using **Jetpack Compose Material 3**.

The interface focuses on:

- Clean navigation
- Modern Android UI
- Responsive layouts
- Media-focused screens
- Simple controls
- Accessible interactions
- Smooth browsing experience

---

# 🧭 Main Sections

GalleryBox provides multiple major areas:

### Pictures
Browse photos and videos stored on the device.

### Albums
Organize media into albums.

### Stories
Create and browse automatically generated or manually created stories.

### Music
Browse and play local music.

### Duo Player
Experience the dedicated dual-track music player.

### Radio
Listen to configured radio stations.

---

# 🔐 Privacy

GalleryBox is designed with privacy in mind.

The goal of the project is to keep personal media under the user's control.

GalleryBox does not require a cloud account for its core local gallery functionality.

The application uses Android permissions to access media and storage features required by the user.

Users should review the permissions requested by the application before granting access.

---

# 📴 Offline-Oriented Design

Many core GalleryBox features are designed to work with media stored locally on the device.

Examples include:

- Photos
- Videos
- Albums
- Favorites
- Trash
- Hidden media
- Local music playback
- Stories

Some features, such as Internet radio or other network-dependent functionality, naturally require an Internet connection.

---

## 🔐 App Lock & Unlock

GalleryBox includes an App Lock feature to help protect personal media and app access.

Features include:

- Lock GalleryBox
- Unlock GalleryBox
- Secure access to the application
- Protection against unauthorized access
- Lock/unlock control from the app's security settings
- Security state management

App Lock is designed to provide an additional layer of privacy for users who want to protect their gallery, music, stories, and other personal content.

The exact authentication method and available security options may depend on the implementation and Android device 

---
# 🏗️ Project Structure

The project is organized around different parts of the GalleryBox application.

Major areas include:

GalleryBox
│
├── Gallery
│   ├── Pictures
│   ├── Albums
│   ├── Favorites
│   ├── Trash
│   ├── Hidden Media
│   └── Stories
│
├── Security
│   └── Lock & Unlock
│
├── Music
│   ├── Music Player
│   ├── Duo Player
│   ├── Queue
│   ├── Equalizer
│   └── Radio
│
├── Video
│   ├── Video Player
│   └── Video Tools
│
├── Database
│   └── Room
│
├── Media
│   ├── MediaStore
│   └── Storage Access Framework
│
└── Background
    └── WorkManager
