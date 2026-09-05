package devPilot.backend.services;

import devPilot.backend.entity.User;
import devPilot.backend.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    public final UserRepository userRepository;
    public final TextEncryptor tokenEncryptor;

    public UserService(UserRepository userRepository, TextEncryptor tokenEncryptor) {
        this.userRepository = userRepository;
        this.tokenEncryptor = tokenEncryptor;
    }


    @Transactional
    public User requiredById(UUID id){
        return userRepository.findById(id).orElseThrow(()-> new IllegalArgumentException("User not found"));
    }

    public String decryptAccessToken(User user){
        return tokenEncryptor.decrypt(user.getAccessToken());
    }

    private static Long toLong(Object value){
    if(value instanceof Number number) {
        return number.longValue();
    }
    return Long.parseLong(String.valueOf(value));
    }
}
