package my.edu.utar.grocerymanagement;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class BarcodeScannerFragment extends Fragment {

    private PreviewView previewView;
    private ExecutorService analysisExecutor;
    private volatile boolean isProcessing = false;

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ProcessCameraProvider cameraProvider;
    private Preview previewUseCase;
    private ImageAnalysis analysisUseCase;
    private boolean cameraBound = false;

    // navigation + duplicate guards
    private final AtomicBoolean isNavigating = new AtomicBoolean(false);
    private long sessionId = 0L;   // invalidates late results
    private String uid;

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    // Ask for camera permission
    private final ActivityResultLauncher<String> camPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCameraIfReady();
                else Toast.makeText(requireContext(), "Camera permission denied", Toast.LENGTH_SHORT).show();
            });

    // Launch AddItemActivity and resume analyzer cleanly on return
    private final ActivityResultLauncher<Intent> addItemLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                isNavigating.set(false);
                sessionId++;     // invalidate any late results
                attachAnalyzer(); // resume scanning
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle b) {
        View v = inflater.inflate(R.layout.fragment_barcode_scanner, container, false);
        previewView = v.findViewById(R.id.previewView);
        analysisExecutor = Executors.newSingleThreadExecutor();
        uid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        return v;
    }

    /** Handle show/hide transitions safely (can be called before view exists) */
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!isAdded() || getView() == null) {
            // View not created yet; nothing to start/stop.
            return;
        }
        updateCameraByVisibility();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Once the view exists, align camera state with current visibility
        updateCameraByVisibility();
    }

    @Override
    public void onResume() {
        super.onResume();
        isProcessing = false;
        isNavigating.set(false);
        sessionId++; // fresh session for analyzer
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCameraIfReady();
        } else {
            camPermLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopCameraSafe();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Clean up camera/analyzer & executor to avoid leaks
        try { if (analysisUseCase != null) analysisUseCase.clearAnalyzer(); } catch (Exception ignored) {}
        try { if (cameraProvider != null) cameraProvider.unbindAll(); } catch (Exception ignored) {}
        cameraBound = false;
        if (analysisExecutor != null) {
            analysisExecutor.shutdownNow();
            analysisExecutor = null;
        }
        previewView = null; // avoid stale references
    }

    /** Centralized logic to start/stop camera based on visibility + view state */
    private void updateCameraByVisibility() {
        if (!isAdded() || getView() == null) return;
        if (isHidden()) {
            stopCameraSafe();
        } else {
            startCameraIfReady();
        }
    }

    /** Start camera only when view is ready and permission is granted */
    private void startCameraIfReady() {
        if (previewView == null) return; // view not ready
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return;

        if (cameraBound) {
            attachAnalyzer(); // ensure analyzer is active
            return;
        }
        if (cameraProvider == null) {
            cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext());
            cameraProviderFuture.addListener(() -> {
                try {
                    if (!isAdded() || previewView == null) return;
                    cameraProvider = cameraProviderFuture.get();
                    bindUseCases();
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Camera init failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }, ContextCompat.getMainExecutor(requireContext()));
        } else {
            bindUseCases();
        }
    }

    private void bindUseCases() {
        if (cameraProvider == null || previewView == null) return;

        // Preview
        if (previewUseCase == null) {
            previewUseCase = new Preview.Builder().build();
        }
        previewUseCase.setSurfaceProvider(previewView.getSurfaceProvider());

        // Analysis
        if (analysisUseCase == null) {
            analysisUseCase = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
        }
        try {
            if (previewView.getDisplay() != null) {
                analysisUseCase.setTargetRotation(previewView.getDisplay().getRotation());
            }
        } catch (Exception ignored) {}

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(
                    getViewLifecycleOwner(),
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase,
                    analysisUseCase
            );
            cameraBound = true;
            attachAnalyzer();
        } catch (Exception e) {
            cameraBound = false;
            Toast.makeText(requireContext(), "Bind failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** Attach/re-attach analyzer safely */
    private void attachAnalyzer() {
        if (analysisUseCase == null || analysisExecutor == null) return;

        try { analysisUseCase.clearAnalyzer(); } catch (Exception ignored) {}
        isProcessing = false;

        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                        Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                        Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
                        Barcode.FORMAT_QR_CODE
                ).build();
        BarcodeScanner scanner = BarcodeScanning.getClient(options);

        final long mySessionId = sessionId;
        analysisUseCase.setAnalyzer(analysisExecutor, image -> {
            if (isNavigating.get()) { image.close(); return; }
            if (isProcessing)       { image.close(); return; }
            isProcessing = true;
            processImage(scanner, image, mySessionId);
        });
    }

    private void stopCameraSafe() {
        // Stop analyzing first
        try { if (analysisUseCase != null) analysisUseCase.clearAnalyzer(); } catch (Exception ignored) {}
        // Unbind camera safely (no need to use previewView.post; just unbind)
        try { if (cameraProvider != null) cameraProvider.unbindAll(); } catch (Exception ignored) {}
        cameraBound = false;
        isProcessing = false;
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void processImage(BarcodeScanner scanner, ImageProxy imageProxy, long mySessionId) {
        try {
            if (imageProxy.getImage() == null) {
                imageProxy.close();
                isProcessing = false;
                return;
            }

            final InputImage img = InputImage.fromMediaImage(
                    imageProxy.getImage(),
                    imageProxy.getImageInfo().getRotationDegrees()
            );

            scanner.process(img)
                    .addOnSuccessListener(barcodes -> {
                        if (mySessionId != sessionId) {
                            isProcessing = false;
                            return;
                        }
                        String code = extractFirstCode(barcodes);
                        if (code != null) {
                            isProcessing = false;
                            handleBarcode(code);
                        } else {
                            isProcessing = false;
                        }
                    })
                    .addOnFailureListener(e -> {
                        if (mySessionId == sessionId) isProcessing = false;
                    })
                    .addOnCompleteListener(task -> imageProxy.close());

        } catch (Exception e) {
            imageProxy.close();
            isProcessing = false;
        }
    }

    private String extractFirstCode(List<Barcode> barcodes) {
        for (Barcode b : barcodes) {
            String raw = b.getRawValue();
            if (raw != null && !raw.trim().isEmpty()) return raw.trim();
        }
        return null;
    }

    /** One-launch guarded flow */
    private void handleBarcode(String barcode) {
        if (uid == null) {
            Toast.makeText(requireContext(), "Please sign in first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isNavigating.compareAndSet(false, true)) {
            // Already navigating; drop this detection
            return;
        }

        // Pause analyzer during navigation to avoid extra launches
        try { if (analysisUseCase != null) analysisUseCase.clearAnalyzer(); } catch (Exception ignored) {}

        // 1) If user already has this barcode, open Edit mode
        db.collection("users").document(uid)
                .collection("grocery_items")
                .whereEqualTo("barcode", barcode)
                .limit(1)
                .get()
                .addOnSuccessListener(qs -> {
                    if (!qs.isEmpty()) {
                        DocumentSnapshot doc = qs.getDocuments().get(0);
                        String productId = doc.getId();
                        Intent i = new Intent(requireContext(), AddItemActivity.class);
                        i.putExtra("productId", productId);
                        addItemLauncher.launch(i);
                    } else {
                        // 2) Otherwise, check catalog
                        db.collection("catalog").document(barcode).get()
                                .addOnSuccessListener(cat -> {
                                    String foundName = cat.exists() ? cat.getString("name") : null;
                                    String foundCategory = cat.exists() ? cat.getString("category") : null;

                                    if (foundName != null && foundCategory != null) {
                                        ConfirmNameCategoryDialog.show(
                                                requireContext(),
                                                barcode,
                                                foundName,
                                                foundCategory,
                                                (finalName, finalCategory, alsoUpdateCatalog) -> {
                                                    Intent i = new Intent(requireContext(), AddItemActivity.class);
                                                    i.putExtra("prefillBarcode", barcode);
                                                    i.putExtra("prefillName", finalName);
                                                    i.putExtra("prefillCategory", finalCategory);
                                                    i.putExtra("allowCatalogUpdate", alsoUpdateCatalog); // ⬅️ carry the user's choice
                                                    addItemLauncher.launch(i);

                                                },
                                                () -> {
                                                    // Cancel → resume analyzer, allow same code again
                                                    isNavigating.set(false);
                                                    sessionId++; // invalidate any late callbacks
                                                    attachAnalyzer();
                                                }
                                        );
                                    } else {
                                        // No catalog entry → go add with just barcode
                                        Intent i = new Intent(requireContext(), AddItemActivity.class);
                                        i.putExtra("prefillBarcode", barcode);
                                        addItemLauncher.launch(i);
                                    }
                                })
                                .addOnFailureListener(e -> {
                                    Toast.makeText(requireContext(), "Catalog lookup failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                    isNavigating.set(false);
                                    attachAnalyzer();
                                });
                    }
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(requireContext(), "Lookup failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    isNavigating.set(false);
                    attachAnalyzer();
                });
    }
}
