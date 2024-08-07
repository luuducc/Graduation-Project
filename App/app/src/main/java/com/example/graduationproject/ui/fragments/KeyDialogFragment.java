package com.example.graduationproject.ui.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.graduationproject.R;
import com.example.graduationproject.config.MyConstant;
import com.example.graduationproject.data.local.MyViewModel;
import com.example.graduationproject.data.local.PrivateKeyToStore;
import com.example.graduationproject.data.local.PublicKeyToStore;
import com.example.graduationproject.data.local.TranscriptToHash;
import com.example.graduationproject.data.remote.Transcript;
import com.example.graduationproject.data.remote.VerifyRequest;
import com.example.graduationproject.data.remote.VerifyResponse;
import com.example.graduationproject.exception.MyException;
import com.example.graduationproject.network.services.SignatureApiService;
import com.example.graduationproject.ui.activities.HomeActivity;
import com.example.graduationproject.ui.adapters.KeyAdapter;
import com.example.graduationproject.utils.DilithiumHelper;
import com.example.graduationproject.utils.FileHelper;
import com.example.graduationproject.utils.HashHelper;
import com.example.graduationproject.utils.KeystoreHelper;
import com.example.graduationproject.utils.callback.VerifyCallback;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumPublicKeyParameters;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class KeyDialogFragment extends androidx.fragment.app.DialogFragment {
    private Transcript selectedTranscript;
    private final String SHARED_PREFERENCES_NAME = MyConstant.SHARED_PREFERENCES_NAME;
    private int modeType;
    private int keyAdapterModeType;
    private MyViewModel myViewModel;
    public static final int MODE_SIGN_JSON = 1;
    public static final int MODE_SIGN_PDF = 2;
    public static final int MODE_SIGN_ALL = 3;
    public static final int MODE_VERIFY_JSON = 4;
    public static final int MODE_VERIFY_PDF = 5;
    public KeyDialogFragment(
            Transcript selectedTranscript, int modeType,
            int keyAdapterModetype) {
        this.selectedTranscript = selectedTranscript;
        this.modeType = modeType;
        this.keyAdapterModeType = keyAdapterModetype;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        View view = inflater.inflate(R.layout.popup_key_list, container, false);
        myViewModel = new ViewModelProvider(requireActivity()).get(MyViewModel.class);

        RecyclerView keyRecyclerView = view.findViewById(R.id.key_list_popup_recycler_view);
        List<PublicKeyToStore> publicKeyToStoreList = FileHelper.retrievePublicKeyFromFile(view.getContext());

        KeyAdapter keyAdapter;
        if (modeType >= 1 && modeType <= 3) { // Sign mode
            keyAdapter = new KeyAdapter(
                    getActivity(),
                    publicKeyToStoreList, keyAdapterModeType,
                    key -> signAndPostTranscript(selectedTranscript, key));
        } else {
            keyAdapter = new KeyAdapter(
                    getActivity(),
                    publicKeyToStoreList, keyAdapterModeType,
                    key -> verifyTranscript(selectedTranscript, key));
        }
        keyRecyclerView.setAdapter(keyAdapter);
        keyRecyclerView.setLayoutManager(new LinearLayoutManager(getActivity(), RecyclerView.VERTICAL, false));
        return view;
    }
    private void signAndPostTranscript(Transcript transcript, PublicKeyToStore publicKeyToStore) {
        if (!publicKeyToStore.isRegistered()) {
            Toast.makeText(getContext(), "Key is not registered", Toast.LENGTH_SHORT).show();
            return;
        }
        List<PrivateKeyToStore> privateKeyList = FileHelper.retrievePrivateKeyFromFile(getContext());
        PrivateKeyToStore privateKeyToStore = null;
        UUID keyId = publicKeyToStore.getUuid();

        // get the corresponding private key
        for (PrivateKeyToStore key : privateKeyList) {
            if (key.getUuid().equals(keyId)) {
                privateKeyToStore = key;
            }
        }
        assert privateKeyToStore != null;

        byte[] encryptedPrivateKeyByte = privateKeyToStore.getEncryptedPrivateKey();
        byte[] privateKeyByte = KeystoreHelper.decryptData(encryptedPrivateKeyByte, KeystoreHelper.getPrivateKey());
        byte[] publicKeyByte = publicKeyToStore.getPublicKey();

        DilithiumPublicKeyParameters publicKeyParameters = DilithiumHelper.retrievePublicKey(publicKeyToStore.getDilithiumParametersType(), publicKeyByte);
        DilithiumPrivateKeyParameters privateKeyParameters = DilithiumHelper.retrievePrivateKey(publicKeyToStore.getDilithiumParametersType(), privateKeyByte, publicKeyParameters);

        switch (modeType) {
            case MODE_SIGN_JSON:
                signJsonTranscript(transcript, keyId.toString(), privateKeyParameters);
                break;
            case MODE_SIGN_PDF:
                signPdfTranscript(transcript, keyId.toString(), privateKeyParameters);
                break;
            case MODE_SIGN_ALL:
                signJsonTranscript(transcript, keyId.toString(), privateKeyParameters);
                signPdfTranscript(transcript, keyId.toString(), privateKeyParameters);
                break;
        }
    }
    private void signJsonTranscript(
            Transcript transcript, String keyId, DilithiumPrivateKeyParameters privateKeyParameters) {
        LocalDateTime currentTime = LocalDateTime.now();
        String currentTimeString = currentTime.toString();
        Log.d("KeyDialogFragment", currentTime.toString());
        String className = transcript.getClassName();
        // get the transcript string and hash it
        Gson gson = new GsonBuilder()
                .setPrettyPrinting().create();
        TranscriptToHash transcriptToHash = TranscriptToHash.transfer(transcript);
        String jsonTranscript = gson.toJson(transcriptToHash);
        String dataToHash = jsonTranscript + currentTimeString;
        byte[] hashedMessage = HashHelper.hashString(dataToHash);
        String initialHash = Base64.getEncoder().encodeToString(hashedMessage);
        // sign the transcript
        byte[] signature = DilithiumHelper.sign(privateKeyParameters, hashedMessage);
        // get the signature string
        String signatureString = Base64.getEncoder().encodeToString(signature);

        sendVerifyRequest(
                className, keyId, initialHash,
                currentTimeString, signatureString, VerifyRequest.JSON_SIGNATURE,
                result -> Toast.makeText(getContext(), "JSON verification result: " + result, Toast.LENGTH_SHORT).show());
    }
    private void signPdfTranscript(
            Transcript transcript, String keyId, DilithiumPrivateKeyParameters privateKeyParameters) {
        try {
            LocalDateTime currentTime = LocalDateTime.now();
            String currentTimeString = currentTime.toString();
            String className = transcript.getClassName();
            // hash PDF
            byte[] hashedMessage = HashHelper.hashPDF(transcript.getClassName(), currentTimeString);
            String initialHash = Base64.getEncoder().encodeToString(hashedMessage);
            // sign the transcript
            byte[] signature = DilithiumHelper.sign(privateKeyParameters, hashedMessage);
            String signatureString = Base64.getEncoder().encodeToString(signature);

            sendVerifyRequest(
                    className, keyId, initialHash,
                    currentTimeString, signatureString, VerifyRequest.PDF_SIGNATURE,
                    result -> Toast.makeText(getContext(), "PDF verification result: " + result, Toast.LENGTH_SHORT).show());
        } catch (MyException e) {
            Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    private void sendVerifyRequest(
            String className, String keyId, String initialHashMessage,
            String currentTime, String signatureString, boolean isPdfElseJson,
            VerifyCallback verifyCallback) {
        // send the verify request
        SignatureApiService signatureApiService = SignatureApiService.getInstance();
        SharedPreferences sharedPreferences = getContext().getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        String accessToken =  sharedPreferences.getString("accessToken", "defaultAccessToken");
        VerifyRequest verifyRequest = new VerifyRequest(
                className, keyId, initialHashMessage,
                currentTime, signatureString, isPdfElseJson);
        signatureApiService.verifyTranscript("Bearer " + accessToken, verifyRequest).enqueue(new Callback<VerifyResponse>() {
            @Override
            public void onResponse(Call<VerifyResponse> call, Response<VerifyResponse> response) {
                if (response.isSuccessful()) {
                    boolean result = response.body().getResult();
                    verifyCallback.onVerifySuccess(result);
                    if (isPdfElseJson) {
                        selectedTranscript.setPdfSignature(signatureString);
                        selectedTranscript.setSignedPdf(true);
                        selectedTranscript.setKeyIdPdf(keyId);
                        selectedTranscript.setSignTimePdf(currentTime);
                    } else {
                        selectedTranscript.setJsonSignature(signatureString);
                        selectedTranscript.setSignedJson(true);
                        selectedTranscript.setKeyIdJson(keyId);
                        selectedTranscript.setSignTimeJson(currentTime);
                    }
                    // Update the View model to re-render the UI
                    myViewModel.updateTranscripts(selectedTranscript);
                } else {
                    Log.d("TranscriptFragment", String.valueOf(response.code())); // http status message
                    try {
                        Log.d("TranscriptFragment", response.errorBody().string());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    if (response.code() == 403) { // token is not valid
                        // delete old access token and navigate to login screen
                        SharedPreferences sharedPreferences = getContext().getSharedPreferences(SHARED_PREFERENCES_NAME, Activity.MODE_PRIVATE);
                        SharedPreferences.Editor editor = sharedPreferences .edit();
                        editor.remove("accessToken");
                        editor.apply();
                        ((HomeActivity) getContext()).navigateToLoginScreen();
                    }
                    Toast.makeText(getContext(), "Response unsuccessful: " + response.errorBody(), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<VerifyResponse> call, Throwable throwable) {
                Log.d("TranscriptFragment", "error when verify");
                if (throwable instanceof IOException) {
                    Log.e("TranscriptFragment", "Network error or conversion error: " + throwable.getMessage());
                    Toast.makeText(getContext(), "Network error or conversion error: " + throwable.getMessage(), Toast.LENGTH_SHORT).show();
                } else {
                    Log.e("TranscriptFragment", "Unexpected error: " + throwable.getMessage());
                    Toast.makeText(getContext(), "Unexpected error: " + throwable.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
    private void verifyTranscript(Transcript transcript, PublicKeyToStore publicKeyToStore) {
        if (!publicKeyToStore.isRegistered()) {
            Toast.makeText(getContext(), "Key is not registered", Toast.LENGTH_SHORT).show();
            return;
        }
        switch (modeType) {
            case MODE_VERIFY_JSON: {
                Gson gson = new GsonBuilder()
                        .setPrettyPrinting().create();
                TranscriptToHash transcriptToHash = TranscriptToHash.transfer(transcript);
                String jsonTranscript = gson.toJson(transcriptToHash);
                String dataToHash = jsonTranscript + transcript.getSignTimeJson();
                byte[] initialHashedMessage = HashHelper.hashString(dataToHash);
                verify(initialHashedMessage, publicKeyToStore, transcript.getJsonSignature());
            }
                break;
            case MODE_VERIFY_PDF:
                try {
                    String className = transcript.getClassName();
                    if (transcript.getSignTimePdf() == null) {
                        Toast.makeText(getContext(), "false", Toast.LENGTH_SHORT).show();
                        break;
                    }
                    // hash PDF
                    byte[] initialHashedMessage = HashHelper.hashPDF(
                            transcript.getClassName(), transcript.getSignTimePdf());
                    verify(initialHashedMessage, publicKeyToStore, transcript.getPdfSignature());
                } catch (MyException e) {
                    Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
                }
                break;
        }
    }
    private void verify(byte[] initialHashedMessage, PublicKeyToStore publicKeyToStore, String signatureString) {
        byte[] publicKeyByte = publicKeyToStore.getPublicKey();
        byte[] signature = Base64.getDecoder().decode(signatureString);
        DilithiumPublicKeyParameters publicKeyParameters = DilithiumHelper.retrievePublicKey(
                publicKeyToStore.getDilithiumParametersType(),
                publicKeyByte);
        boolean result = DilithiumHelper.verify(
                publicKeyParameters,initialHashedMessage, signature
        );
        Toast.makeText(getContext(), "" + result, Toast.LENGTH_SHORT).show();
    }
}
