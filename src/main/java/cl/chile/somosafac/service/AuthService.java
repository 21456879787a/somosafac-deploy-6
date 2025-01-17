package cl.chile.somosafac.service;

import cl.chile.somosafac.DTO.FamiliaDTO;
import cl.chile.somosafac.DTO.PasswordDTO;
import cl.chile.somosafac.DTO.UsuarioDTO;
import cl.chile.somosafac.entity.FamiliaEntity;
import cl.chile.somosafac.entity.NotificacionEntity;
import cl.chile.somosafac.entity.UsuarioEntity;
import cl.chile.somosafac.mapper.FamiliaMapperManual;
import cl.chile.somosafac.repository.FamiliaRepository;
import cl.chile.somosafac.repository.NotificacionRepository;
import cl.chile.somosafac.repository.UsuarioRepository;
import cl.chile.somosafac.security.JwtService;
import cl.chile.somosafac.security.LoginRequest;
import cl.chile.somosafac.security.RegisterRequest;
import cl.chile.somosafac.security.Role;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
    private long tiempoExpiracionResetToken = 86400000; // 1 dia - Consultar con equipo

    private final UsuarioRepository usuarioRepository;
    private final FamiliaRepository familiaRepository;
    private final NotificacionRepository notificacionRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;

    public UsuarioDTO login(LoginRequest request, HttpServletResponse response) {
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(request.getCorreo(), request.getContrasenaHash()));
        UsuarioEntity usuario = usuarioRepository.findByCorreo(request.getCorreo())
                .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado."));

        usuario.setFechaUltimoAcceso(LocalDateTime.now());
        usuarioRepository.save(usuario);

        String token = jwtService.getToken(usuario);
        // Configuración de la cookie
        Cookie jwtCookie = new Cookie("token", token);
        jwtCookie.setHttpOnly(true);
        jwtCookie.setPath("/");
        jwtCookie.setSecure(true);
        jwtCookie.setMaxAge(24 * 60 * 60); // 24 horas
        response.addCookie(jwtCookie);

        return UsuarioDTO.fromEntity(usuario);
    }

    public UsuarioDTO register(RegisterRequest request) {
        if (usuarioRepository.findByCorreo(request.getCorreo()).isPresent()) {
            throw new RuntimeException("El correo ya está en uso");
        }

        UsuarioEntity usuario = UsuarioEntity.builder()
                .nombre(request.getNombre())
                .apellido(request.getApellido())
                .correo(request.getCorreo())
                .contrasenaHash(passwordEncoder.encode(request.getContrasenaHash()))
                .cargo(request.getCargo())
                .tipoUsuario(request.getTipoUsuario())
                .fechaRegistro(LocalDateTime.now())
                .aceptarTerminos(request.isAceptarTerminos())
                .build();

        usuarioRepository.save(usuario);

        // Verificar si el rol del usuario es "familia"
        if (request.getTipoUsuario().equals(Role.FAMILIA)) {
            FamiliaEntity familia = new FamiliaEntity();
            // Configurar los datos necesarios para la familia
            familia.setNombreFaUno(usuario.getNombre());
            familia.setEmail(usuario.getCorreo());
            familia.setUsuario(usuario);

            familiaRepository.save(familia);

            NotificacionEntity notificacion = new NotificacionEntity();
            notificacion.setUsuario(usuario);
            notificacion.setMensaje("Se ha creado un nuevo usuario con el rol de familia.");
            notificacion.setFechaEnvio(LocalDateTime.now());
            notificacion.setVisto(false);
            notificacionRepository.save(notificacion);
        }


        return UsuarioDTO.fromEntity(usuario);
    }

    public UsuarioDTO cambiarContrasenaPrimerIngreso(String email, PasswordDTO nuevaContrasena) {
        UsuarioEntity usuario = usuarioRepository.findByCorreo(email)
                .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado."));

        try {
            usuario.setContrasenaHash(passwordEncoder.encode(nuevaContrasena.getContrasenaHash()));
            usuario.setPrimerIngreso(false);
            usuarioRepository.save(usuario);

            return UsuarioDTO.fromEntity(usuario);
        } catch (Exception e) {
            throw new RuntimeException("Error al actualizar contraseña.");
        }
    }

    // Recuperar contraseña
    public String generarResetToken(String email) {
        UsuarioEntity usuario = usuarioRepository.findByCorreo(email)
                .orElseThrow(() -> new UsernameNotFoundException("Usuario con correo: " + email + " no encontrado."));

        String token = UUID.randomUUID().toString();
        usuario.setResetToken(token);
        usuario.setFechaExpiracionResetToken(LocalDateTime.now().plusMinutes(tiempoExpiracionResetToken));
        usuarioRepository.save(usuario);

        return token;
    }

    public UsuarioEntity validarResetToken(String token) {
        Optional<UsuarioEntity> usuario = usuarioRepository.findByResetToken(token);
        if (usuario.isEmpty() || esTokenExpirado(usuario.get().getFechaExpiracionResetToken())) {
            throw new RuntimeException("El código que recibiste en el email ya no es válido o ha expirado.");
        }
        return usuario.get();
    }

    public Boolean esTokenExpirado(LocalDateTime fechaExpiracionToken) {
        return fechaExpiracionToken.isBefore(LocalDateTime.now());
    }

    public void resetContrasena(UsuarioEntity usuario, String nuevaContrasena) {
        usuario.setContrasenaHash(passwordEncoder.encode(nuevaContrasena));
        usuario.setResetToken(null);
        usuario.setFechaExpiracionResetToken(null);
        usuarioRepository.save(usuario);
    }
}
