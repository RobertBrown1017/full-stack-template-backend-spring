package com.example.fullstacktemplate.service;

import com.example.fullstacktemplate.config.AppProperties;
import com.example.fullstacktemplate.dto.ChangePasswordDto;
import com.example.fullstacktemplate.dto.SignUpRequestDto;
import com.example.fullstacktemplate.dto.TokenAccessRequestDto;
import com.example.fullstacktemplate.exception.BadRequestException;
import com.example.fullstacktemplate.exception.UnauthorizedRequestException;
import com.example.fullstacktemplate.model.*;
import com.example.fullstacktemplate.repository.FileDbRepository;
import com.example.fullstacktemplate.repository.TokenRepository;
import com.example.fullstacktemplate.repository.UserRepository;
import com.example.fullstacktemplate.security.JwtTokenProvider;
import dev.samstevens.totp.secret.SecretGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Optional;

@Service
@Transactional
public class UserService {
    public static final String REFRESH_TOKEN_COOKIE_NAME = "rt_cookie";
    private final PasswordEncoder passwordEncoder;
    private final FileDbService fileDbService;
    private final SecretGenerator twoFactorSecretGenerator;
    private final TokenRepository tokenRepository;
    private final AppProperties appProperties;
    private final JwtTokenProvider jwtTokenProvider;
    private final ResourceLoader resourceLoader;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final FileDbRepository fileDbRepository;

    @Autowired
    public UserService(PasswordEncoder passwordEncoder, FileDbService fileDbService, SecretGenerator twoFactorSecretGenerator, AppProperties appProperties, JwtTokenProvider jwtTokenProvider, TokenRepository tokenRepository, ResourceLoader resourceLoader, UserRepository userRepository, EmailService emailService, FileDbRepository fileDbRepository) {
        this.passwordEncoder = passwordEncoder;
        this.fileDbService = fileDbService;
        this.twoFactorSecretGenerator = twoFactorSecretGenerator;
        this.appProperties = appProperties;
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenRepository = tokenRepository;
        this.resourceLoader = resourceLoader;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.fileDbRepository = fileDbRepository;
    }

    public JwtToken createToken(User user, String value, TokenType tokenType) {
        JwtToken jwtToken = new JwtToken();
        jwtToken.setValue(value);
        jwtToken.setUser(user);
        jwtToken.setTokenType(tokenType);
        return tokenRepository.save(jwtToken);
    }

    public User createNewUser(SignUpRequestDto signUpRequestDto) throws IOException {
        User user = new User();
        user.setEmailVerified(false);
        user.setName(signUpRequestDto.getName());
        user.setEmail(signUpRequestDto.getEmail());
        user.setPassword(signUpRequestDto.getPassword());
        user.setProvider(AuthProvider.local);
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setTwoFactorEnabled(false);
        user.setProfileImage(fileDbService.save("blank-profile-picture.png", FileType.IMAGE_PNG, resourceLoader.getResource("classpath:images\\blank-profile-picture.png").getInputStream().readAllBytes()));
        return userRepository.save(user);
    }

    public Cookie createRefreshTokenCookie(String refreshTokenValue, Integer refreshTokenExpirationMillis) {
        Cookie cookie = new Cookie(REFRESH_TOKEN_COOKIE_NAME, refreshTokenValue);
        cookie.setMaxAge(refreshTokenExpirationMillis);
        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        return cookie;
    }

    public Cookie createEmptyRefreshTokenCookie() {
        Cookie cookie = new Cookie(REFRESH_TOKEN_COOKIE_NAME, "");
        cookie.setMaxAge(1);
        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        return cookie;
    }

    public User updateUserPassword(User user, String newPassword) {
        user.setPassword(passwordEncoder.encode(newPassword));
        return userRepository.save(user);
    }


    public Optional<JwtToken> getRefreshTokenFromRequest(HttpServletRequest request) {
        if (request.getCookies() != null) {
            return Arrays.stream(request.getCookies())
                    .filter(cookie -> REFRESH_TOKEN_COOKIE_NAME.equals(cookie.getName()))
                    .findFirst()
                    .flatMap(cookie -> tokenRepository.findByValueAndTokenType(cookie.getValue(), TokenType.REFRESH));
        }
        return Optional.empty();
    }

    @Cacheable(cacheNames = "user", key = "#id")
    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public void cancelUserAccount(Long userId) {
        userRepository.deleteById(userId);
    }

    public User updatePassword(User user, ChangePasswordDto changePasswordDto) {
        if (passwordEncoder.matches(changePasswordDto.getCurrentPassword(), user.getPassword())) {
            user.setPassword(passwordEncoder.encode(changePasswordDto.getNewPassword()));
            return userRepository.save(user);
        } else {
            throw new UnauthorizedRequestException();
        }
    }


    public User activateUserAccount(TokenAccessRequestDto tokenAccessRequestDto) {
        Optional<JwtToken> optionalVerificationToken = tokenRepository.findByValueAndTokenType(tokenAccessRequestDto.getToken(), TokenType.ACCOUNT_ACTIVATION);
        if (optionalVerificationToken.isPresent()) {
            User user = optionalVerificationToken.get().getUser();
            if (!jwtTokenProvider.validateToken(tokenAccessRequestDto.getToken())) {
                throw new BadRequestException("tokenExpired");
            } else {
                user.setEmailVerified(true);
                userRepository.save(user);
                tokenRepository.delete(optionalVerificationToken.get());
            }
            return userRepository.save(user);
        }
        throw new BadRequestException("invalidToken");
    }

    public User disableTwoFactorAuthentication(User user) {
        user.setTwoFactorSecret(null);
        user.setTwoFactorEnabled(false);
        user.getTwoFactorRecoveryCodes().clear();
        return userRepository.save(user);
    }

    public User enableTwoFactorAuthentication(User user) {
        user.setTwoFactorEnabled(true);
        return userRepository.save(user);
    }

    public User activateRequestedEmail(TokenAccessRequestDto tokenAccessRequestDto) {
        Optional<JwtToken> optionalVerificationToken = tokenRepository.findByValueAndTokenType(tokenAccessRequestDto.getToken(), TokenType.EMAIL_UPDATE);
        if (optionalVerificationToken.isPresent()) {
            User user = optionalVerificationToken.get().getUser();
            if (!jwtTokenProvider.validateToken(tokenAccessRequestDto.getToken())) {
                throw new BadRequestException("tokenExpired");
            } else {
                user.setEmail(user.getRequestedNewEmail());
                user.setRequestedNewEmail(null);
                userRepository.save(user);
                tokenRepository.delete(optionalVerificationToken.get());
                return user;
            }
        }
        throw new BadRequestException("invalidToken");
    }

    public User updateProfile(Long currentUserId, User newUser) throws MalformedURLException, URISyntaxException {
        User user = findById(currentUserId).orElseThrow(() -> new BadRequestException("userNotFound"));
        if (!newUser.getEmail().equals(user.getEmail()) && isEmailUsed(newUser.getEmail())) {
            throw new BadRequestException("emailInUse");
        }
        if (!newUser.getName().equals(user.getName()) && isUsernameUsed(newUser.getName())) {
            throw new BadRequestException("usernameInUse");
        }
        String newEmail = newUser.getRequestedNewEmail();
        if (user.getRequestedNewEmail() != null && !user.getEmail().equals(newUser.getRequestedNewEmail())) {
            String tokenValue = jwtTokenProvider.createTokenValue(user.getId(), Duration.of(appProperties.getAuth().getVerificationTokenExpirationMsec(), ChronoUnit.MILLIS));
            createToken(user, tokenValue, TokenType.EMAIL_UPDATE);
            emailService.sendEmailChangeConfirmationMessage(newEmail, user.getEmail(), tokenValue);
        }
        return userRepository.save(newUser);
    }

    public boolean isUsernameUsed(String username) {
        return userRepository.existsByName(username);
    }

    public boolean isEmailUsed(String email) {
        return userRepository.existsByEmail(email);
    }
}
