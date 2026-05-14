package com.example.user.service;

import com.example.user.dto.KeycloakUserProvisionRequest;
import com.example.user.dto.RegisterRequest;
import com.example.user.model.User;
import com.example.user.repository.UserRepository;
import com.example.user.repository.UserAuditEventRepository;
import com.example.user.authz.AuthorizationContextService;
import com.example.commonauth.AuthorizationContext;
import com.example.user.permission.UserAuditMirrorClient;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Mockito'yu JUnit 5 ile kullanmak için bu anotasyon gereklidir.
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    // @Mock: Bu bağımlılıkların sahte (mock) versiyonları oluşturulacak.
    // Gerçek veritabanına veya parola şifreleyiciye gitmeyeceğiz.
    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserAuditEventRepository userAuditEventRepository;

    @Mock
    private UserAuditMirrorClient userAuditMirrorClient;

    private FakeAuthzService authorizationContextService;

    private UserAuditEventService userAuditEventService;

    // @InjectMocks: Test edeceğimiz asıl sınıf budur.
    // Mockito, yukarıda oluşturulan sahte (@Mock) nesneleri bu sınıfa enjekte eder.
    private UserService userService;

    private RegisterRequest registerRequest;
    private User user;
    private User userWithCompany;

    // Her testten önce bu metot çalışır ve test verilerini hazırlar.
    @BeforeEach
    void setUp() {
        registerRequest = new RegisterRequest();
        registerRequest.setName("Test User");
        registerRequest.setEmail("test@example.com");
        registerRequest.setPassword("password123");

        user = new User();
        user.setId(1L);
        user.setName("Test User");
        user.setEmail("test@example.com");
        user.setCompanyId(null); // global

        userWithCompany = new User();
        userWithCompany.setId(2L);
        userWithCompany.setName("Company User");
        userWithCompany.setEmail("c1@example.com");
        userWithCompany.setCompanyId(10L);

        userAuditEventService = new UserAuditEventService(userAuditEventRepository, userAuditMirrorClient);
        authorizationContextService = new FakeAuthzService();
        userService = new UserService(userRepository, passwordEncoder, userAuditEventService, authorizationContextService, 1440);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void registerUser_WhenEmailDoesNotExist_ShouldRegisterSuccessfully() {
        // --- ARRANGE (Hazırlık) ---
        // Mockito'ya, userRepository.existsByEmail metodu çağrıldığında
        // ne olursa olsun 'false' döndürmesini söylüyoruz.
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);

        // Parola şifreleme metodunun sahte bir şifrelenmiş değer döndürmesini sağlıyoruz.
        when(passwordEncoder.encode("password123")).thenReturn("hashed_password");

        // userRepository.save metodu herhangi bir User nesnesiyle çağrıldığında,
        // hazırladığımız 'user' nesnesini döndürmesini söylüyoruz.
        when(userRepository.save(any(User.class))).thenReturn(user);

        // --- ACT (Eylem) ---
        // Test edeceğimiz asıl metodu çağırıyoruz.
        User savedUser = userService.registerUser(registerRequest);

        // --- ASSERT (Doğrulama) ---
        // Sonuçların beklediğimiz gibi olup olmadığını kontrol ediyoruz.
        assertNotNull(savedUser); // Kaydedilen kullanıcı null olmamalı.
        assertEquals("test@example.com", savedUser.getEmail()); // Email'ler eşleşmeli.
        System.out.println(">>> Başarılı kullanıcı kaydı testi geçti!");
    }

    @Test
    void registerUser_WhenEmailExists_ShouldThrowException() {
        // --- ARRANGE (Hazırlık) ---
        // Bu senaryoda, email'in zaten var olduğunu varsayıyoruz.
        // Mockito'ya, existsByEmail çağrıldığında 'true' döndürmesini söylüyoruz.
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        // --- ACT & ASSERT (Eylem ve Doğrulama) ---
        // registerUser metodunun bir IllegalStateException fırlatıp fırlatmadığını test ediyoruz.
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            userService.registerUser(registerRequest);
        });

        // Fırlatılan hatanın mesajının doğru olup olmadığını kontrol ediyoruz.
        assertEquals("Bu email adresi zaten kullanılıyor.", exception.getMessage());
        System.out.println(">>> Mevcut email hatası testi geçti!");
    }

    @Test
    void searchUsers_superAdminSeesAll() {
        AuthorizationContext adminCtx = AuthorizationContext.of(1L, "admin@example.com", java.util.Set.of("ADMIN"), java.util.Set.of("ADMIN"));
        authorizationContextService.setCtx(adminCtx);
        Authentication authentication = new StubAuthentication(null);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        Pageable pageable = PageRequest.of(0, 10);
        java.util.List<User> users = java.util.List.of(user, user);
        when(userRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(users, pageable, users.size()));

        Page<User> result = userService.searchUsers(null, null, null, null, pageable);

        assertEquals(2, result.getTotalElements());
    }

    @Test
    void searchUsers_scopedUserGetsOnlyOwnCompanies() {
        AuthorizationContext scopedCtx = AuthorizationContext.of(2L, "user@example.com", java.util.Set.of(), java.util.Set.of("USER_READ"), java.util.Set.of(10L), java.util.Set.of(), java.util.Set.of());
        authorizationContextService.setCtx(scopedCtx);
        Authentication authentication = new StubAuthentication(null);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        Pageable pageable = PageRequest.of(0, 10);
        java.util.List<User> companyUsers = java.util.List.of(userWithCompany, user); // company + global (null)
        when(userRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(companyUsers, pageable, companyUsers.size()));

        Page<User> result = userService.searchUsers(null, null, null, null, pageable);

        assertEquals(2, result.getTotalElements());
    }

    @Test
    void searchUsers_scopedUserWithoutCompaniesGetsEmpty() {
        AuthorizationContext scopedCtx = AuthorizationContext.of(2L, "user@example.com", java.util.Set.of(), java.util.Set.of("USER_READ"), java.util.Set.of(), java.util.Set.of(), java.util.Set.of());
        authorizationContextService.setCtx(scopedCtx);
        Authentication authentication = new StubAuthentication(null);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        Pageable pageable = PageRequest.of(0, 10);
        java.util.List<User> globalOnly = java.util.List.of(user);
        when(userRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(globalOnly, pageable, globalOnly.size()));

        Page<User> result = userService.searchUsers(null, null, null, null, pageable);

        assertEquals(1, result.getTotalElements()); // sadece company_id NULL kayıtlar görünür
        verify(userRepository, never()).findAll(any(Pageable.class));
        verify(userRepository).findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class));
    }

    // ------------------------------------------------------------------
    // kc_subject auto-backfill regression tests (Codex 019e2022 follow-up,
    // BUG #1 prevention at the provisioning boundary).
    //
    // Pinning the contract: when /api/v1/users/internal/provision is
    // called with a `kcSubject` field, the persisted User row must carry
    // it. Without that, freshly-provisioned users land with kc_subject=null
    // and the auth-service impersonation broker rejects every attempt to
    // impersonate them with TARGET_SUBJECT_UNRESOLVABLE (Step 1f).
    // ------------------------------------------------------------------

    @Test
    void provisionFromKeycloak_persistsKcSubjectWhenSupplied() {
        // arrange — fresh user (existsByEmail false), DTO carries kcSubject
        KeycloakUserProvisionRequest request = new KeycloakUserProvisionRequest();
        request.setEmail("new.user@example.com");
        request.setName("New User");
        request.setEnabled(true);
        request.setRole("USER");
        request.setKcSubject("kc-subject-uuid-fresh");

        when(userRepository.findByEmail("new.user@example.com")).thenReturn(java.util.Optional.empty());
        when(passwordEncoder.encode(any(String.class))).thenReturn("random-encoded");
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        when(userRepository.save(savedUser.capture())).thenAnswer(inv -> inv.getArgument(0));

        // act
        User result = userService.provisionFromKeycloak(request);

        // assert — captured user has kcSubject from request
        assertEquals("kc-subject-uuid-fresh", savedUser.getValue().getKcSubject());
        assertEquals("kc-subject-uuid-fresh", result.getKcSubject());
    }

    @Test
    void provisionFromKeycloak_preservesExistingKcSubjectWhenRequestOmitsIt() {
        // arrange — existing user has a kcSubject, request omits the field
        User existing = new User();
        existing.setId(42L);
        existing.setEmail("existing@example.com");
        existing.setName("Existing Name");
        existing.setKcSubject("kc-subject-already-set");
        existing.setEnabled(true);

        KeycloakUserProvisionRequest request = new KeycloakUserProvisionRequest();
        request.setEmail("existing@example.com");
        request.setName("Updated Name");
        // kcSubject intentionally left null — older callers that don't
        // know about the field must NOT wipe a backfilled subject.

        when(userRepository.findByEmail("existing@example.com")).thenReturn(java.util.Optional.of(existing));
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        when(userRepository.save(savedUser.capture())).thenAnswer(inv -> inv.getArgument(0));

        // act
        userService.provisionFromKeycloak(request);

        // assert — the captured user kept the existing kcSubject untouched
        assertEquals("kc-subject-already-set", savedUser.getValue().getKcSubject());
    }

    @Test
    void provisionFromKeycloak_leavesKcSubjectNullWhenRequestOmitsAndUserIsFresh() {
        // arrange — fresh user, request omits kcSubject (older caller)
        KeycloakUserProvisionRequest request = new KeycloakUserProvisionRequest();
        request.setEmail("legacy@example.com");
        request.setName("Legacy Provision");
        request.setEnabled(true);
        // kcSubject left null

        when(userRepository.findByEmail("legacy@example.com")).thenReturn(java.util.Optional.empty());
        when(passwordEncoder.encode(any(String.class))).thenReturn("random-encoded");
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        when(userRepository.save(savedUser.capture())).thenAnswer(inv -> inv.getArgument(0));

        // act
        userService.provisionFromKeycloak(request);

        // assert — kcSubject stays null. This pins the regression: such
        // users CANNOT be impersonated until the kc_subject backfill
        // runbook (RB-kc-subject-backfill.md) is run. New callers SHOULD
        // supply the value at provision time.
        assertNull(savedUser.getValue().getKcSubject());
    }

    @Test
    void registerUser_doesNotPopulateKcSubject_documentedGap() {
        // BUG #1 prevention documentation: the (deprecated) registerUser
        // path does NOT know about Keycloak and therefore leaves
        // kc_subject null. Future callers that need impersonable users
        // must go through /internal/provision with a kcSubject value, or
        // run the backfill runbook afterwards.
        when(userRepository.existsByEmail(any(String.class))).thenReturn(false);
        when(passwordEncoder.encode(any(String.class))).thenReturn("hashed");
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        when(userRepository.save(savedUser.capture())).thenAnswer(inv -> inv.getArgument(0));

        userService.registerUser(registerRequest);

        // Pin the current behaviour — change this assertion when the
        // register path learns about Keycloak subject seeding.
        assertNull(savedUser.getValue().getKcSubject());
    }
}

/**
 * Basit bir sahte AuthorizationContextService; gerçek HTTP çağrısı yapmaz.
 */
class FakeAuthzService extends AuthorizationContextService {

    private AuthorizationContext ctx = AuthorizationContext.of(null, null, java.util.Set.of(), java.util.Set.of());

    FakeAuthzService() {
        super(null, null, "");
    }

    void setCtx(AuthorizationContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public AuthorizationContext buildContext(org.springframework.security.oauth2.jwt.Jwt jwt, java.util.List<org.springframework.security.core.GrantedAuthority> authorities) {
        return ctx;
    }

    @Override
    public AuthorizationContext getCurrentUserContext() {
        return ctx;
    }
}

/**
 * Basit Authentication stub'u; Mockito inline mock gerektirmeden SecurityContext'e konur.
 */
class StubAuthentication implements Authentication {
    private final Object principal;
    private boolean authenticated = true;

    StubAuthentication(Object principal) {
        this.principal = principal;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return java.util.List.of();
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getDetails() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public boolean isAuthenticated() {
        return authenticated;
    }

    @Override
    public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {
        this.authenticated = isAuthenticated;
    }

    @Override
    public String getName() {
        return principal != null ? principal.toString() : "";
    }
}
