package HMS.example.HospitalManagementSystem.controller;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import HMS.example.HospitalManagementSystem.model.Contact;
import HMS.example.HospitalManagementSystem.model.*;
import HMS.example.HospitalManagementSystem.service.EmailService;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.ArrayList;
import java.util.Random;
import java.util.UUID;

@Controller
public class HMSController {
	// 🔐 Track active logged-in users (username → session)
	private static final java.util.concurrent.ConcurrentHashMap<String, HttpSession>
	        activeUserSessions = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Logger log = LoggerFactory.getLogger(HMSController.class);

    // 🔐 Generic login error (no hint about account type)
    private static final String INVALID_LOGIN_MSG = "Invalid username, password, or role.";

    // Folder under working directory: <project-dir>/uploads/doctors
    private static final String DOCTOR_UPLOAD_DIR = "uploads/doctors";

    @Autowired
    private SessionFactory sf;

    // Email service (best-effort)
    @Autowired
    private EmailService emailService;

    // ---------- helper to check if patient profile is incomplete ----------
    private boolean isPatientProfileIncomplete(Patient p) {
        if (p == null) return true;
        if (p.getName() == null || p.getName().trim().isEmpty()) return true;
        if (p.getPhone() == null || p.getPhone().trim().isEmpty()) return true;
        if (p.getAddress() == null || p.getAddress().trim().isEmpty()) return true;
        if (p.getGender() == null || p.getGender().trim().isEmpty()) return true;
        if (p.getAge() <= 0) return true;
        return false;
    }
    

    // ---------- helper to check if doctor profile is incomplete ----------
    private boolean isDoctorProfileIncomplete(Doctor d) {
        if (d == null) return true;
        if (d.getName() == null || d.getName().trim().isEmpty()) return true;
        // if default name == email, treat as incomplete
        if (d.getEmail() != null &&
                d.getName() != null &&
                d.getName().trim().equalsIgnoreCase(d.getEmail().trim())) return true;
        if (d.getPhone() == null || d.getPhone().trim().isEmpty()) return true;

        String spec = d.getSpecialization() == null ? "" : d.getSpecialization().trim();
        if (spec.isEmpty()) return true;

        // <<< removed the rule that treated "general" as incomplete >>>
        return false;
    }

    //  ---------- Home / Landing page ----------
    @GetMapping({"/", "/home"})
    public String HomePage(Model model) {

        Session session = sf.openSession();
        try {
            // 🔹 Only APPROVED doctors, ordered by name
            Query<Doctor> dq = session.createQuery(
                    "from Doctor d where d.approved = true order by d.name",
                    Doctor.class
            );
            dq.setMaxResults(4); // show top 4 on home page

            List<Doctor> topDoctors = dq.list();
            model.addAttribute("topDoctors", topDoctors);

        } catch (Exception ex) {
            ex.printStackTrace();
            // in case of error, send empty list so Thymeleaf doesn't break
            model.addAttribute("topDoctors", new ArrayList<Doctor>());
        } finally {
            session.close();
        }

        return "home";
    }

    // ---------- show login page (GET) ----------
    @GetMapping("/login")
    public String showLoginPage() {
        return "login";   // login.html
    }

 // ---------- process login (POST) ----------
    @PostMapping("/login")
    public String login(@RequestParam("username") String username,
                        @RequestParam("password") String password,
                        @RequestParam("role") String role,
                        HttpSession httpSession,
                        Model model) {

        Session session = sf.openSession();
        Transaction tx = null;

        try {
            tx = session.beginTransaction();

            String uname = username != null ? username.trim() : "";
            String r = role != null ? role.trim().toLowerCase() : "";

            // for repopulating form on error
            model.addAttribute("lastUsername", uname);
            model.addAttribute("lastRole", r);

            // 1) Find login row
            Login dblogin = session.get(Login.class, uname); // username is PK

            // ❌ generic message if no such user
            if (dblogin == null) {
                model.addAttribute("msg", INVALID_LOGIN_MSG);
                return "login";
            }

            // DEBUG
            System.out.println("LOGIN DEBUG -> inputUser=" + uname + ", inputRole=" + r);

            // ❌ generic message if password mismatch
            if (!dblogin.getPassword().equals(password)) {
                model.addAttribute("msg", INVALID_LOGIN_MSG);
                return "login";
            }

            // ❌ generic message if role mismatch
            if (dblogin.getRole() == null || !dblogin.getRole().equalsIgnoreCase(r)) {
                model.addAttribute("msg", INVALID_LOGIN_MSG);
                return "login";
            }

            
            // NEW LOGIC: SINGLE SESSION ENFORCEMENT
           
            if (activeUserSessions.containsKey(uname)) {
                HttpSession existingSession = activeUserSessions.get(uname);
                try {
                    // We try to access the existing session to see if it's still valid
                    existingSession.getCreationTime(); 
                    
                    // If line above didn't throw exception, the user is still logged in elsewhere
                    model.addAttribute("msg", "You are already logged in on another device or browser.");
                    return "login";
                    
                } catch (IllegalStateException e) {
                    // The old session existed in our map but was invalidated (timed out),
                    // so we clean up the map and allow the new login to proceed.
                    activeUserSessions.remove(uname);
                }
            }
          


            // 1) Email verification check
            if (("patient".equalsIgnoreCase(r) || "doctor".equalsIgnoreCase(r)) &&
                    (dblogin.getEmailVerified() == null || !dblogin.getEmailVerified())) {
                tx.commit();
                model.addAttribute("msg", "Please verify your email before logging in.");
                return "login";
            }

            // 2) Based on role
            switch (r) {

                case "patient": {
                    Patient patient;
                    Query<Patient> pq = session.createQuery("from Patient where lower(trim(email)) = :e", Patient.class);
                    pq.setParameter("e", uname.toLowerCase());
                    patient = pq.uniqueResult();

                    if (patient == null) {
                        patient = new Patient();
                        patient.setEmail(uname);
                        patient.setName(uname);
                        // ... set defaults
                        session.save(patient);
                    }

                    boolean incomplete = isPatientProfileIncomplete(patient);
                    tx.commit();

                    httpSession.setAttribute("patientId", patient.getId());
                    httpSession.setAttribute("patientName", patient.getName());
                    httpSession.setAttribute("role", "patient");
                    httpSession.setAttribute("username", uname);

                   
                    activeUserSessions.put(uname, httpSession);

                    if (incomplete) return "redirect:/patient/details";
                    return "redirect:/patient/dashboard";
                }

                case "doctor": {
                    Doctor doctor = null;
                    Query<Doctor> dq = session.createQuery(
                            "from Doctor where lower(trim(email)) = :u or lower(trim(name)) = :u", Doctor.class);
                    dq.setParameter("u", uname.toLowerCase());
                    doctor = dq.uniqueResult();

                    if (doctor == null) {
                        try {
                            Long did = Long.parseLong(uname);
                            doctor = session.get(Doctor.class, did);
                        } catch (NumberFormatException ignore) {}
                    }

                    if (doctor == null) {
                        if (tx != null) tx.rollback();
                        model.addAttribute("msg", INVALID_LOGIN_MSG);
                        return "login";
                    }

                    if (!doctor.isApproved()) {
                        tx.commit();
                        model.addAttribute("msg", "Account pending approval.");
                        return "login";
                    }

                    boolean incomplete = isDoctorProfileIncomplete(doctor);

                    httpSession.setAttribute("doctorId", doctor.getId());
                    httpSession.setAttribute("doctorName", doctor.getName());
                    httpSession.setAttribute("role", "doctor");
                    httpSession.setAttribute("username", uname);

                    tx.commit();
                    
                   
                    activeUserSessions.put(uname, httpSession);

                    if (incomplete) return "redirect:/doctor/details";
                    return "redirect:/doctor/dashboard";
                }

                case "admin":
                    tx.commit();
                    httpSession.setAttribute("role", "admin");
                    httpSession.setAttribute("username", uname);
                    
                  
                    activeUserSessions.put(uname, httpSession);
                    
                    return "redirect:/admin/dashboard";

                default:
                    model.addAttribute("msg", INVALID_LOGIN_MSG);
                    return "login";
            }

        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            ex.printStackTrace();
            model.addAttribute("msg", "Error during login: " + ex.getMessage());
            return "login";
        } finally {
            session.close();
        }
    }
    @RequestMapping("/logoutPage")
    public String logoutPage(HttpSession session) {

        Object unameObj = session.getAttribute("username");
        if (unameObj != null) {
            String uname = unameObj.toString();
            activeUserSessions.remove(uname);
        }

        session.invalidate();
        return "home";
    }

 

    // ---------- signup ----------
    @RequestMapping("SignupPage")
    public String signupPage() {
        return "signup";
    }
    @RequestMapping("/signup")
    public String signup(@ModelAttribute Login login, Model model) {

        String uname = login.getUsername() != null ? login.getUsername().trim() : "";
        String pwd   = login.getPassword() != null ? login.getPassword().trim() : "";
        String role  = login.getRole() != null ? login.getRole().trim() : "";

        if (uname.isEmpty() || pwd.isEmpty()) {
            model.addAttribute("msg", "Username and password are required.");
            return "signup";
        }

        Session session = sf.openSession();
        Transaction tx = null;

        try {
            tx = session.beginTransaction();

            // 1) Duplicate username in Login
            Login existingLogin = session.get(Login.class, uname);
            if (existingLogin != null) {
                if (tx != null) tx.rollback();
                session.close();
                model.addAttribute("msg", "Username or email ID already exists. Please use a different one.");
                return "signup";
            }

            // 2) For PATIENT, also check Patient.email
            if ("patient".equalsIgnoreCase(role)) {
                Query<Long> emailCountQ = session.createQuery(
                        "select count(p.id) from Patient p where lower(trim(p.email)) = :e",
                        Long.class
                );
                emailCountQ.setParameter("e", uname.toLowerCase());
                Long emailCount = emailCountQ.uniqueResult();
                if (emailCount != null && emailCount > 0) {
                    if (tx != null) tx.rollback();
                    session.close();
                    model.addAttribute("msg", "Username or email ID already exists. Please use a different one.");
                    return "signup";
                }
            }

            String code = UUID.randomUUID().toString();
            login.setUsername(uname);
            login.setPassword(pwd);
            login.setRole(role);
            login.setEmailVerified(Boolean.FALSE);
            login.setVerificationCode(code);

            session.save(login);
            tx.commit();
            session.close();

            // 3) Send Verification Email
            try {
                String encodedUser = URLEncoder.encode(uname, "UTF-8");
                String encodedCode = URLEncoder.encode(code, "UTF-8");
                String verifyLink = "http://localhost:8080/verify?username=" + encodedUser + "&code=" + encodedCode;

                emailService.sendVerificationEmail(uname, verifyLink);
            } catch (Exception mailEx) {
                log.error("Failed to send verification email to {}: {}", uname, mailEx.toString(), mailEx);
            }

            // --- KEY CHANGE HERE ---
            // Pass the username to the view so the "Resend" button works automatically
            model.addAttribute("username", uname);
            
            model.addAttribute("msg", "Sign up successful. Please check your email to verify your account before login.");
            return "verify_pending";

        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            // Ensure session is closed if an error occurs while it's still open
            if (session.isOpen()) {
                session.close();
            }
            ex.printStackTrace();
            model.addAttribute("msg", "Error during signup: " + ex.getMessage());
            return "signup";
        }
    }

    @GetMapping("/resendVerification")
    public String resendVerificationPage() {
        return "resend_verification";
    }

    @PostMapping("/resendVerification")
    public String resendVerification(@RequestParam String username, Model model) {

        Session session = sf.openSession();
        Transaction tx = null;

        try {
            tx = session.beginTransaction();
            Login login = session.get(Login.class, username.trim());

            if (login == null) {
                model.addAttribute("msg",
                    "If this account exists, a verification email has been sent.");
                return "resend_verification";
            }

            if (Boolean.TRUE.equals(login.getEmailVerified())) {
                model.addAttribute("msg", "Email already verified. Please login.");
                return "login";
            }

            String code = UUID.randomUUID().toString();
            login.setVerificationCode(code);
            session.update(login);
            tx.commit();

            String link = "http://localhost:8080/verify?username="
                    + URLEncoder.encode(username, "UTF-8")
                    + "&code="
                    + URLEncoder.encode(code, "UTF-8");

            emailService.sendVerificationEmail(username, link);

            model.addAttribute("msg", "Verification email sent again.");
            return "verify_pending";

        } catch (Exception e) {
            if (tx != null) tx.rollback();
            model.addAttribute("msg", "Error sending verification email.");
            return "resend_verification";
        } finally {
            session.close();
        }
    }


    // ---------- FORGOT PASSWORD (REQUEST) ----------
    @GetMapping("/forgotPasswordPage")
    public String forgotPasswordPage() {
        return "forgot_password";
    }

    @PostMapping("/forgotPassword")
    public String handleForgotPassword(@RequestParam("username") String username,
                                       Model model) {

        String uname = username != null ? username.trim() : "";
        if (uname.isEmpty()) {
            model.addAttribute("msg", "Please enter your email / username.");
            return "forgot_password";
        }

        Session session = sf.openSession();
        Transaction tx = null;
        try {
            tx = session.beginTransaction();

            Login login = session.get(Login.class, uname);
            if (login == null) {
                // do not reveal if user exists or not (security)
                model.addAttribute("msg", "If this account exists, a reset link has been sent to the registered email.");
                return "forgot_password";
            }

            if (login.getEmailVerified() == null || !login.getEmailVerified()) {
                model.addAttribute("msg", "Please verify your email first before resetting password.");
                return "forgot_password";
            }

            // generate reset code
            String resetCode = UUID.randomUUID().toString();
            login.setVerificationCode(resetCode);   // reusing the same field
            session.update(login);
            tx.commit();

            // send email with reset link
            try {
                String encodedUser = URLEncoder.encode(uname, "UTF-8");
                String encodedCode = URLEncoder.encode(resetCode, "UTF-8");
                String resetLink = "http://localhost:8080/resetPassword?username="
                        + encodedUser + "&code=" + encodedCode;

                emailService.sendPasswordResetEmail(uname, resetLink);
            } catch (Exception e) {
                e.printStackTrace();
            }

            model.addAttribute("msg", "If this account exists, a reset link has been sent to the registered email.");
            return "home";

        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            ex.printStackTrace();
            model.addAttribute("msg", "Error while processing request: " + ex.getMessage());
            return "forgot_password";
        } finally {
            session.close();
        }
    }// ---------- DELETE DOCTOR ACCOUNT ----------
    @PostMapping("/doctor/deleteAccount")
    public String deleteDoctorAccount(HttpSession session, Model model) {
        Object didObj = session.getAttribute("doctorId");
        Object unameObj = session.getAttribute("username");

        if (didObj == null || unameObj == null) {
            model.addAttribute("msg", "Session expired.");
            return "home";
        }

        Long doctorId = (didObj instanceof Long) ? (Long) didObj : Long.parseLong(didObj.toString());
        String username = unameObj.toString();

        Session ss = sf.openSession();
        Transaction tx = null;
        try {
            tx = ss.beginTransaction();

            Doctor d = ss.get(Doctor.class, doctorId);
            Login l = ss.get(Login.class, username);

            if (d != null) {
                // 1. Delete Photo file if exists
                if (d.getPhotoPath() != null) {
                    try {
                        String projectDir = System.getProperty("user.dir");
                        String storedPath = d.getPhotoPath();
                        if (storedPath.startsWith("/") || storedPath.startsWith("\\")) {
                            storedPath = storedPath.substring(1);
                        }
                        Path path = Paths.get(projectDir, storedPath);
                        Files.deleteIfExists(path);
                    } catch (Exception e) {
                         // ignore file error
                    }
                }

                // 2. Handle Appointments (Set doctor to null OR delete them)
                // Usually we keep appointments but set doctor_id to null so patient history remains.
                // But for hard delete:
                Query<?> qAppt = ss.createQuery("delete from Appointment a where a.doctor.id = :did");
                qAppt.setParameter("did", doctorId);
                qAppt.executeUpdate();

                // 3. Delete Doctor Profile
                ss.delete(d);
            }

            if (l != null) {
                // 4. Delete Login
                ss.delete(l);
            }

            tx.commit();

            // 5. Logout
            activeUserSessions.remove(username);
            session.invalidate();

            model.addAttribute("msg", "Doctor account deleted successfully.");
            return "home";

        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            ex.printStackTrace();
            model.addAttribute("msg", "Error deleting account: " + ex.getMessage());
            return "redirect:/doctor/dashboard";
        } finally {
            ss.close();
        }
        }
    
    // ---------- DELETE PATIENT ACCOUNT ----------
    @PostMapping("/patient/deleteAccount")
    public String deletePatientAccount(HttpSession session, Model model) {
        // 1. Check Session
        Object pidObj = session.getAttribute("patientId");
        Object unameObj = session.getAttribute("username");

        if (pidObj == null || unameObj == null) {
            model.addAttribute("msg", "Session expired. Please login again.");
            return "home";
        }

        Long patientId = (pidObj instanceof Long) ? (Long) pidObj : Long.parseLong(pidObj.toString());
        String username = unameObj.toString();

        Session ss = sf.openSession();
        Transaction tx = null;
        try {
            tx = ss.beginTransaction();

            // 2. Load the Patient and Login entities
            Patient p = ss.get(Patient.class, patientId);
            Login l = ss.get(Login.class, username);

            if (p != null) {
                // --- STEP A: Delete Appointments (Foreign Key Constraint) ---
                ss.createQuery("delete from Appointment a where a.patient.id = :pid")
                  .setParameter("pid", patientId)
                  .executeUpdate();

                // --- STEP B: Delete Messages ---
                ss.createQuery("delete from Message m where m.patient.id = :pid")
                  .setParameter("pid", patientId)
                  .executeUpdate();

                // --- STEP C: Delete Medical Records ---
                ss.createQuery("delete from MedicalRecord m where m.patient.id = :pid")
                  .setParameter("pid", patientId)
                  .executeUpdate();

                // --- STEP D: Delete the Patient Profile ---
                ss.delete(p);
            }

            if (l != null) {
                // --- STEP E: Delete the Login Credential ---
                ss.delete(l);
            }

            tx.commit();

            // 3. Logout the user
            activeUserSessions.remove(username);
            session.invalidate();

            model.addAttribute("msg", "Your account has been permanently deleted.");
            return "home";

        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            ex.printStackTrace();
            model.addAttribute("msg", "Error deleting account: " + ex.getMessage());
            return "redirect:/patient/dashboard";
        } finally {
            ss.close();
        }
    }
 // ---------- RESET PASSWORD (FORM) ----------
    @GetMapping("/resetPassword")
    public String resetPasswordPage(@RequestParam(value = "username", required = false) String usernameFromUrl,
                                    @RequestParam("code") String code,
                                    Model model) {

        System.out.println("DEBUG: Link Clicked. Code: " + code);

        Session session = sf.openSession();
        try {
            
            Query<Login> q = session.createQuery("from Login where verificationCode = :code", Login.class);
            q.setParameter("code", code);
            Login login = q.uniqueResult();

            // 1. Check if user exists with this code
            if (login == null) {
                System.out.println("DEBUG: No user found for code: " + code);
                model.addAttribute("msg", "Invalid or expired reset link.");
                return "home";
            }

           
            model.addAttribute("username", login.getUsername()); 
            model.addAttribute("code", code);
            
            return "reset_password";

        } catch (Exception ex) {
            ex.printStackTrace();
            model.addAttribute("msg", "Error: " + ex.getMessage());
            return "home";
        } finally {
            session.close();
        }
    }

    // ---------- RESET PASSWORD (SAVE) ----------
    @PostMapping("/resetPassword")
    public String handleResetPassword(@RequestParam("username") String username,
                                      @RequestParam("code") String code,
                                      @RequestParam("password") String password,
                                      @RequestParam("confirmPassword") String confirmPassword,
                                      Model model) {

        if (password == null || password.trim().isEmpty()) {
            model.addAttribute("msg", "Password cannot be empty.");
            model.addAttribute("username", username);
            model.addAttribute("code", code);
            return "reset_password";
        }
        if (!password.equals(confirmPassword)) {
            model.addAttribute("msg", "Passwords do not match.");
            model.addAttribute("username", username);
            model.addAttribute("code", code);
            return "reset_password";
        }

        Session session = sf.openSession();
        Transaction tx = null;
        try {
            tx = session.beginTransaction();

            Login login = session.get(Login.class, username.trim());
            if (login == null ||
                    login.getVerificationCode() == null ||
                    !login.getVerificationCode().equals(code)) {

                if (tx != null) tx.rollback();
                model.addAttribute("msg", "Invalid or expired reset link.");
                return "home";
            }

            // update password
            login.setPassword(password.trim());         
            login.setVerificationCode(null);            
            session.update(login);
            tx.commit();

            model.addAttribute("msg", "Password reset successful. Please login with your new password.");
            return "home";

        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            ex.printStackTrace();
            model.addAttribute("msg", "Error resetting password: " + ex.getMessage());
            return "reset_password";
        } finally {
            session.close();
        }
    }

    // ---------- EMAIL VERIFICATION ----------

    @GetMapping("/verify")
    public String verifyEmail(@RequestParam("username") String username,
                              @RequestParam("code") String code,
                              Model model) {

        // ---------- FIRST: verify Login only ----------
        Session session = sf.openSession();
        Transaction tx = null;
        Login login;

        try {
            tx = session.beginTransaction();

            String uname = username.trim();
            login = session.get(Login.class, uname);

            if (login == null) {
                model.addAttribute("msg", "Invalid verification link (no such user).");
                return "home";
            }

            if (Boolean.TRUE.equals(login.getEmailVerified())) {
                model.addAttribute("msg", "Your email is already verified. Please log in.");
                return "home";
            }

            if (login.getVerificationCode() == null ||
                    !login.getVerificationCode().equals(code)) {
                model.addAttribute("msg", "Invalid or expired verification code.");
                return "home";
            }

            // ✅ mark email verified
            login.setEmailVerified(Boolean.TRUE);
            login.setVerificationCode(null);
            session.update(login);

            tx.commit();   // <-- only Login updated here

        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            ex.printStackTrace();
            model.addAttribute("msg", "Error verifying email: " + ex.getMessage());
            return "home";
        } finally {
            session.close();
        }

        // ---------- SECOND: best-effort create Doctor row if role=doctor ----------
        try {
            if ("doctor".equalsIgnoreCase(login.getRole())) {

                Session s2 = sf.openSession();
                Transaction tx2 = null;
                try {
                    tx2 = s2.beginTransaction();

                    String uname = login.getUsername().trim().toLowerCase();

                    // check if doctor already exists with this email
                    Doctor doctor = s2.createQuery(
                                    "from Doctor d where lower(trim(d.email)) = :e",
                                    Doctor.class
                            )
                            .setParameter("e", uname)
                            .uniqueResult();

                    if (doctor == null) {
                        doctor = new Doctor();
                        doctor.setEmail(login.getUsername());      // email as username
                        // leave name/phone/specialization minimal so we can detect as incomplete
                        doctor.setName(login.getUsername());       // will be changed in fill details
                        doctor.setPhone("");                       // empty for now
                        doctor.setSpecialization("General");       // default
                        doctor.setApproved(false);                 // pending
                        doctor.setApprovedAt(null);

                        s2.save(doctor);
                    }

                    tx2.commit();
                } catch (Exception ex2) {
                    if (tx2 != null) tx2.rollback();
                    // 🔇 DO NOT show error to user, just log to console
                    ex2.printStackTrace();
                } finally {
                    s2.close();
                }
            }
        } catch (Exception ignore) {
            // fully ignore – verification is already done
        }

        // ---------- final message to user ----------
        model.addAttribute("msg", "Email verified successfully. You can now log in.");
        return "home";
    }

    // ---------- patient details (profile) ----------
    @GetMapping("/patient/details")
    public String showPatientDetails(HttpSession httpSession, Model model) {
        Object pidObj = httpSession.getAttribute("patientId");
        if (pidObj == null) {
            model.addAttribute("msg", "Please login as patient first.");
            return "home";
        }

        Long patientId;
        if (pidObj instanceof Long) patientId = (Long) pidObj;
        else if (pidObj instanceof Integer) patientId = ((Integer) pidObj).longValue();
        else patientId = Long.parseLong(pidObj.toString());

        Session session = sf.openSession();
        try {
            Patient patient = session.get(Patient.class, patientId);
            model.addAttribute("patient", patient);
            return "patient_details";
        } finally {
            session.close();
        }
    }

    @PostMapping("/patient/details")
    public String savePatientDetails(@RequestParam("id") Long id,
                                     @RequestParam("name") String name,
                                     @RequestParam("age") Integer age,
                                     @RequestParam("phone") String phone,
                                     @RequestParam("gender") String gender,
                                     @RequestParam("address") String address,
                                     @RequestParam(value = "disease", required = false) String disease,
                                     HttpSession httpSession,
                                     Model model) {

        Object pidObj = httpSession.getAttribute("patientId");
        if (pidObj == null) {
            model.addAttribute("msg", "Please login as patient first.");
            return "home";
        }

        Session session = sf.openSession();
        Transaction tx = null;
        try {
            tx = session.beginTransaction();

            Patient p = session.get(Patient.class, id);
            if (p == null) {
                model.addAttribute("msg", "Patient not found.");
                return "patient_details";
            }

            String phoneTrimmed = phone != null ? phone.trim() : "";

            if (!phoneTrimmed.isEmpty()) {
                Query<Long> phoneCountQ = session.createQuery(
                        "select count(p2.id) from Patient p2 where p2.phone = :ph and p2.id <> :id",
                        Long.class
                );
                phoneCountQ.setParameter("ph", phoneTrimmed);
                phoneCountQ.setParameter("id", id);
                Long phoneCount = phoneCountQ.uniqueResult();

                if (phoneCount != null && phoneCount > 0) {
                    if (tx != null) tx.rollback();
                    model.addAttribute("msg", "Phone number already exists. Please use a different number.");
                    model.addAttribute("patient", p);
                    return "patient_details";
                }
            }

            p.setName(name != null ? name.trim() : p.getName());
            p.setAge(age != null ? age : p.getAge());
            p.setPhone(phoneTrimmed);
            p.setGender(gender != null ? gender.trim() : p.getGender());
            p.setAddress(address != null ? address.trim() : p.getAddress());
            p.setDisease(disease != null ? disease.trim() : p.getDisease());

            session.update(p);
            tx.commit();

            httpSession.setAttribute("patientName", p.getName());

            return "redirect:/patient/dashboard";

        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            ex.printStackTrace();
            model.addAttribute("msg", "Error saving details: " + ex.getMessage());
            return "patient_details";
        } finally {
            session.close();
        }
    }

    // ---------- DOCTOR DETAILS (profile) ----------
    @GetMapping("/doctor/details")
    public String showDoctorDetails(HttpSession httpSession, Model model) {
        Object didObj = httpSession.getAttribute("doctorId");
        if (didObj == null) {
            model.addAttribute("msg", "Please login as doctor first.");
            return "home";
        }

        Long doctorId;
        if (didObj instanceof Long) doctorId = (Long) didObj;
        else if (didObj instanceof Integer) doctorId = ((Integer) didObj).longValue();
        else doctorId = Long.parseLong(didObj.toString());

        Session session = sf.openSession();
        try {
            Doctor doctor = session.get(Doctor.class, doctorId);
            if (doctor == null) {
                model.addAttribute("msg", "Doctor not found.");
                return "home";
            }
            model.addAttribute("doctor", doctor);
            return "fill_doctor_details";   // your Thymeleaf template
        } finally {
            session.close();
        }
    }
    @PostMapping("/doctor/details")
    public String saveDoctorDetails(
            @RequestParam("id") Long id,
            @RequestParam("name") String name,
            @RequestParam("specialization") String specialization,
            @RequestParam(value = "phone", required = false) String phone,
            @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,

            // ✅ FIX 1: Use boolean. Spring automatically converts "true"/"false" string to boolean.
            @RequestParam(value = "removePhoto", defaultValue = "false") boolean removePhoto,

            HttpSession httpSession,
            Model model) {

        // Debugging: Check console to see if this prints "true" when you click save
        System.out.println("DOCTOR PROFILE UPDATE -> ID: " + id + " | Remove Photo? " + removePhoto);

        Object didObj = httpSession.getAttribute("doctorId");
        if (didObj == null) {
            model.addAttribute("msg", "Please login as doctor first.");
            return "home";
        }

        // Safe casting of Session ID
        Long sessionDoctorId = (didObj instanceof Long) ? (Long) didObj : Long.parseLong(didObj.toString());

        if (!sessionDoctorId.equals(id)) {
            model.addAttribute("msg", "Invalid doctor id.");
            return "home";
        }

        Session session = sf.openSession();
        Transaction tx = null;

        try {
            tx = session.beginTransaction();

            Doctor d = session.get(Doctor.class, id);
            if (d == null) {
                model.addAttribute("msg", "Doctor not found.");
                return "fill_doctor_details";
            }

            // --- Update Basic Fields ---
            d.setName(name != null ? name.trim() : d.getName());
            d.setSpecialization(specialization != null ? specialization.trim() : d.getSpecialization());
            if (phone != null) d.setPhone(phone.trim());


            // --- PHOTO LOGIC ---

            // 1. Check Removal Request First
            if (removePhoto) {
                System.out.println("Removing photo for doctor: " + d.getName());

                if (d.getPhotoPath() != null && !d.getPhotoPath().isEmpty()) {
                    try {
                        String projectDir = System.getProperty("user.dir");
                        
                        // ✅ FIX 2: Path sanitization. Remove leading slash if present.
                        String storedPath = d.getPhotoPath();
                        if (storedPath.startsWith("/") || storedPath.startsWith("\\")) {
                            storedPath = storedPath.substring(1);
                        }

                        Path oldFilePath = Paths.get(projectDir, storedPath);
                        
                        // Delete the file physically
                        boolean deleted = Files.deleteIfExists(oldFilePath);
                        System.out.println("File deleted physicaly? " + deleted + " Path: " + oldFilePath);

                    } catch (IOException e) {
                        log.error("Failed to delete doctor image", e);
                        // Continue execution even if file delete fails, we must update DB
                    }
                }

                // Set DB reference to null
                d.setPhotoPath(null);
            } 
            // 2. If NOT removing, Check for New Upload
            else if (imageFile != null && !imageFile.isEmpty()) {
                
                String projectDir = System.getProperty("user.dir");
                Path uploadRoot = Paths.get(projectDir, DOCTOR_UPLOAD_DIR);
                if (!Files.exists(uploadRoot)) {
                    Files.createDirectories(uploadRoot);
                }

                String originalName = imageFile.getOriginalFilename();
                String ext = (originalName != null && originalName.contains("."))
                        ? originalName.substring(originalName.lastIndexOf("."))
                        : ".png";

                // Unique filename to prevent caching issues
                String fileName = "doctor_" + d.getId() + "_" + System.currentTimeMillis() + ext;
                Path filePath = uploadRoot.resolve(fileName);

                imageFile.transferTo(filePath.toFile());

                // Save with leading slash for web access
                d.setPhotoPath("/" + DOCTOR_UPLOAD_DIR + "/" + fileName);

                log.info("Doctor {} image uploaded at {}", d.getId(), filePath);
            }

            session.update(d);
            tx.commit();

            httpSession.setAttribute("doctorName", d.getName());
            return "redirect:/doctor/dashboard";

        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            ex.printStackTrace();
            model.addAttribute("msg", "Error saving doctor details.");
            return "redirect:/doctor/details";
        } finally {
            session.close();
        }
    }
    // ---------- other simple mappings ----------
    @RequestMapping("patient")
    public String patientView() { return "patient"; }

    @RequestMapping("servicePage")
    public String service() { return "service"; }

    @RequestMapping("aboutPage")
    public String about() { return "about"; }
    
    
    @GetMapping("/cardiologyPage")
    public String cardiologyPage(Model model) {
        Session session = sf.openSession();

        List<Doctor> cardiologists = session.createQuery(
            "FROM Doctor d WHERE d.specialization = :spec AND d.approved = true",
            Doctor.class
        ).setParameter("spec", "Cardiology")
         .getResultList();

        model.addAttribute("cardiologists", cardiologists);
        return "cardiology"; // cardiology.html
    }

    @GetMapping("/neurologyPage")
    public String neurologyPage(Model model) {
        Session session = sf.openSession();

        List<Doctor> neurologys = session.createQuery(
            "FROM Doctor d WHERE d.specialization = :spec AND d.approved = true",
            Doctor.class
        ).setParameter("spec", "Neurology")
         .getResultList();

        model.addAttribute("neurologys", neurologys);
        return "neurology"; // neurology.html
    }
    
    @GetMapping("/pediatricPage")
    public String pediatricPage(Model model) {
        Session session = sf.openSession();

        List<Doctor> pediatrics = session.createQuery(
            "FROM Doctor d WHERE d.specialization = :spec AND d.approved = true",
            Doctor.class
        ).setParameter("spec", "Pediatric")
         .getResultList();

        model.addAttribute("pediatrics", pediatrics);
        return "pediatric"; //pediatric.html
    }
    @GetMapping("/surgeryPage")
    public String surgeryPage(Model model) {
        Session session = sf.openSession();

        List<Doctor> surgerys = session.createQuery(
            "FROM Doctor d WHERE d.specialization = :spec AND d.approved = true",
            Doctor.class
        ).setParameter("spec", "Surgeons")
         .getResultList();

        model.addAttribute("surgerys", surgerys);
        return "surgery"; //surgery.html
    }

    @RequestMapping("contactPage")
    public String contact() { return "contact"; }

 // ---------- submit contact form ----------
    @PostMapping("/contact")
    public String contactSubmit(@ModelAttribute Contact contact, Model model) {

        Session session = sf.openSession();
        Transaction tx = null;
        try {
            tx = session.beginTransaction();
            session.save(contact);
            tx.commit();
        } finally {
            session.close();
        }

        model.addAttribute("msg", "Thank you! Your message has been sent successfully.");
        return "home";
    }

    

    @RequestMapping("doctors")
    public String DoctorPage () {
        return "doctor";
    }

    @RequestMapping("termsPage")
    public String terms() { return "terms"; }

    @RequestMapping("/overview")
    public String overview() { return "overview"; }

    @RequestMapping("/emergency")
    public String emergency() { return "emeg"; }

    // ---------- patient dashboard ----------
    @GetMapping({"/patient", "/patient/", "/patient/dashboard"})
    public String patientDashboard(Model model, HttpSession session) {
        Object pidObj = session.getAttribute("patientId");
        if (pidObj == null) {
            model.addAttribute("msg", "Please login as patient to access the dashboard.");
            return "home";
        }

        Long patientId;
        if (pidObj instanceof Long) patientId = (Long) pidObj;
        else if (pidObj instanceof Integer) patientId = ((Integer) pidObj).longValue();
        else patientId = Long.parseLong(pidObj.toString());

        Session ss = sf.openSession();
        try {
            Patient patient = ss.get(Patient.class, patientId);
            if (patient != null) {
                model.addAttribute("patient", patient);
                model.addAttribute("patientName", patient.getName());
                session.setAttribute("patientName", patient.getName());
            } else {
                model.addAttribute("patientName", session.getAttribute("patientName"));
            }

            try {
                Query<Long> q = ss.createQuery(
                        "select count(a.id) from Appointment a where a.patient.id = :pid and a.appointmentTime >= :now",
                        Long.class);
                q.setParameter("pid", patientId);
                q.setParameter("now", LocalDateTime.now());
                Long upcoming = q.uniqueResult();
                model.addAttribute("upcomingCount", upcoming != null ? upcoming : 0L);
            } catch (Exception ex) {
                Query<Long> q2 = ss.createQuery(
                        "select count(a.id) from Appointment a where a.patient.id = :pid",
                        Long.class);
                q2.setParameter("pid", patientId);
                Long total = q2.uniqueResult();
                model.addAttribute("upcomingCount", total != null ? total : 0L);
            }

            //  Only APPROVED doctors shown to patients
            Query<Doctor> dq = ss.createQuery(
                    "from Doctor d where d.approved = true order by d.name",
                    Doctor.class
            );
            List<Doctor> doctors = dq.list();
            model.addAttribute("doctors", doctors);

            List<PrescriptionRow> prescriptions = new ArrayList<>();
            try {
                Query<Object[]> pq = ss.createQuery(
                        "select m.doctor.name, m.recordDate, m.prescription " +
                                "from MedicalRecord m " +
                                "where m.patient.id = :pid and m.prescription is not null " +
                                "order by m.recordDate desc", Object[].class);
                pq.setParameter("pid", patientId);
                pq.setMaxResults(10);
                List<Object[]> presRaw = pq.list();
                for (Object[] r : presRaw) {
                    String doctorName = r[0] != null ? r[0].toString() : "-";
                    LocalDate date = r[1] != null ? (LocalDate) r[1] : null;
                    String summary = r[2] != null ? r[2].toString() : "-";
                    if (summary.length() > 200) summary = summary.substring(0, 200) + "...";
                    prescriptions.add(new PrescriptionRow(doctorName, date, summary));
                }
            } catch (Exception ex) {
                // ignore
            }
            model.addAttribute("prescriptions", prescriptions);

            List<Message> messages = new ArrayList<>();
            try {
                Query<Message> mq = ss.createQuery(
                        "from Message m where m.patient.id = :pid order by m.sentAt desc",
                        Message.class);
                mq.setParameter("pid", patientId);
                mq.setMaxResults(10);
                messages = mq.list();
            } catch (Exception ex) { }
            model.addAttribute("messages", messages);

            try {
                Query<Appointment> apq = ss.createQuery(
                        "from Appointment a where a.patient.id = :pid and a.status = :st and a.appointmentTime >= :now order by a.appointmentTime asc",
                        Appointment.class);
                apq.setParameter("pid", patientId);
                apq.setParameter("st", AppointmentStatus.SCHEDULED);
                apq.setParameter("now", LocalDateTime.now());
                apq.setMaxResults(1);
                Appointment nextAp = apq.uniqueResult();
                model.addAttribute("nextAppointmentDate", nextAp != null ? nextAp.getAppointmentTime() : null);
            } catch (Exception ex) {
                model.addAttribute("nextAppointmentDate", null);
            }

            model.addAttribute("year", LocalDate.now().getYear());

            return "patient_dashboard";
        } finally {
            ss.close();
        }
    }

    // ---------- list appointments ----------
    @GetMapping("/patient/appointments")
    public String listAppointmentsMerged(Model model, HttpSession session,
                                         @RequestParam(value = "showCancelled", defaultValue = "false") boolean showCancelled) {
        Object pidObj = session.getAttribute("patientId");
        if (pidObj == null) {
            model.addAttribute("msg", "Please login first.");
            return "home";
        }
        Long patientId = (pidObj instanceof Long) ? (Long) pidObj : Long.parseLong(pidObj.toString());

        model.addAttribute("showCancelled", showCancelled);

        Session ss = sf.openSession();
        try {
            Query<Appointment> q;
            if (showCancelled) {
                q = ss.createQuery(
                        "from Appointment a where a.patient.id = :pid order by a.appointmentTime desc",
                        Appointment.class);
                q.setParameter("pid", patientId);
            } else {
                q = ss.createQuery(
                        "from Appointment a where a.patient.id = :pid and a.status != :st order by a.appointmentTime desc",
                        Appointment.class);
                q.setParameter("pid", patientId);
                q.setParameter("st", AppointmentStatus.CANCELLED);
            }
            List<Appointment> list = q.list();
            model.addAttribute("appointments", list);
            return "patient_appointments";
        } finally {
            ss.close();
        }
    }

    // ---------- view a single appointment ----------
    @GetMapping("/patient/appointments/{id}")
    public String appointmentDetailsMerged(@PathVariable("id") Long id, Model model, HttpSession session) {
        Object pidObj = session.getAttribute("patientId");
        if (pidObj == null) {
            model.addAttribute("msg", "Please login first.");
            return "home";
        }
        Long patientId = (pidObj instanceof Long) ? (Long) pidObj : Long.parseLong(pidObj.toString());

        Session ss = sf.openSession();
        try {
            Appointment ap = ss.get(Appointment.class, id);
            if (ap == null || ap.getPatient() == null || !ap.getPatient().getId().equals(patientId)) {
                model.addAttribute("msg", "Appointment not found or access denied.");
                return "patient_appointments";
            }
            model.addAttribute("appointment", ap);
            return "patient_appointment_details";
        } finally {
            ss.close();
        }
    }

    // ---------- confirmation page ----------
    @GetMapping("/patient/appointments/confirmation/{id}")
    public String appointmentConfirmation(@PathVariable("id") Long id, Model model, HttpSession session) {
        Object pidObj = session.getAttribute("patientId");
        if (pidObj == null) {
            model.addAttribute("msg", "Please login first.");
            return "home";
        }
        Long patientId = (pidObj instanceof Long) ? (Long) pidObj : Long.parseLong(pidObj.toString());

        Session ss = sf.openSession();
        try {
            Appointment ap = ss.get(Appointment.class, id);
            if (ap == null || ap.getPatient() == null || !ap.getPatient().getId().equals(patientId)) {
                model.addAttribute("msg", "Appointment not found or access denied.");
                return "patient_appointments";
            }
            model.addAttribute("appointment", ap);
            model.addAttribute("patientName", session.getAttribute("patientName"));
            return "appointment_confirmation";
        } finally {
            ss.close();
        }
    }

    // ---------- edit appointment (GET) ----------
    @GetMapping("/patient/appointments/{id}/edit")
    public String editAppointmentForm(@PathVariable("id") Long id, Model model, HttpSession session) {
        Object pidObj = session.getAttribute("patientId");
        if (pidObj == null) {
            model.addAttribute("msg", "Please login first.");
            return "home";
        }
        Long patientId = (pidObj instanceof Long) ? (Long) pidObj : Long.parseLong(pidObj.toString());

        Session ss = sf.openSession();
        try {
            Appointment ap = ss.get(Appointment.class, id);
            if (ap == null || ap.getPatient() == null || !ap.getPatient().getId().equals(patientId)) {
                model.addAttribute("msg", "Appointment not found or access denied.");
                return "patient_appointments";
            }

            // 🔴 Only approved doctors in dropdown
            Query<Doctor> dq = ss.createQuery(
                    "from Doctor d where d.approved = true order by d.name",
                    Doctor.class
            );
            List<Doctor> doctors = dq.list();
            model.addAttribute("doctors", doctors);
            model.addAttribute("appointment", ap);
            return "patient_appointment_edit";
        } finally {
            ss.close();
        }
    }

    // ---------- edit appointment (POST) ----------
    @PostMapping("/patient/appointments/{id}/edit")
    public String editAppointmentSubmit(@PathVariable("id") Long id,
                                        @RequestParam("doctorId") Long doctorId,
                                        @RequestParam("time") String appointmentTimeRaw,
                                        @RequestParam(value="notes", required=false) String notes,
                                        Model model, HttpSession session) {
        Object pidObj = session.getAttribute("patientId");
        if (pidObj == null) {
            model.addAttribute("msg", "Please login first.");
            return "home";
        }
        Long patientId = (pidObj instanceof Long) ? (Long) pidObj : Long.parseLong(pidObj.toString());

        LocalDateTime appointmentTime;
        try {
            appointmentTime = LocalDateTime.parse(appointmentTimeRaw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ex) {
            model.addAttribute("msg", "Invalid date/time format. Please use date-time picker.");
            return "redirect:/patient/appointments/" + id + "/edit";
        }

        Session ss = sf.openSession();
        Transaction tx = null;
        try {
            tx = ss.beginTransaction();
            Appointment ap = ss.get(Appointment.class, id);
            if (ap == null || ap.getPatient() == null || !ap.getPatient().getId().equals(patientId)) {
                model.addAttribute("msg", "Appointment not found or access denied.");
                if (tx != null) tx.rollback();
                return "patient_appointments";
            }

            Doctor doc = ss.get(Doctor.class, doctorId);
            if (doc == null) {
                model.addAttribute("msg", "Selected doctor not found.");
                if (tx != null) tx.rollback();
                return "redirect:/patient/appointments/" + id + "/edit";
            }

            // (Optional) also enforce approved on edit
            if (!doc.isApproved()) {
                model.addAttribute("msg", "Cannot assign an unapproved doctor to this appointment.");
                if (tx != null) tx.rollback();
                return "redirect:/patient/appointments/" + id + "/edit";
            }

            Query<Long> conflictQ = ss.createQuery(
                    "select count(a.id) from Appointment a where a.doctor.id = :did and a.appointmentTime = :t and a.status = :st and a.id != :id",
                    Long.class);
            conflictQ.setParameter("did", doctorId);
            conflictQ.setParameter("t", appointmentTime);
            conflictQ.setParameter("st", AppointmentStatus.BOOKED);
            conflictQ.setParameter("id", id);
            Long conflict = conflictQ.uniqueResult();
            if (conflict != null && conflict > 0) {
                model.addAttribute("msg", "Selected time is already booked. Please choose another time.");
                if (tx != null) tx.rollback();
                return "redirect:/patient/appointments/" + id + "/edit";
            }

            ap.setDoctor(doc);
            ap.setAppointmentTime(appointmentTime);
            ap.setNotes(notes != null ? notes : ap.getNotes());
            ap.setStatus(AppointmentStatus.BOOKED);

            ss.update(ap);
            tx.commit();

            model.addAttribute("msg", "Appointment updated.");
            return "redirect:/patient/appointments";
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            model.addAttribute("msg", "Error updating appointment: " + ex.getMessage());
            return "redirect:/patient/appointments/" + id + "/edit";
        } finally {
            ss.close();
        }
    }

    // ---------- book appointment (patient) ----------
    @PostMapping("/patient/appointments/book")
    public String bookAppointmentMerged(@RequestParam("doctorId") Long doctorId,
                                        @RequestParam("time") String appointmentTimeRaw,
                                        @RequestParam(value="notes", required=false) String notes,
                                        Model model, HttpSession session) {
        Object pidObj = session.getAttribute("patientId");
        if (pidObj == null) {
            model.addAttribute("msg", "Please login first.");
            return "home";
        }
        Long patientId = (pidObj instanceof Long) ? (Long) pidObj : Long.parseLong(pidObj.toString());

        LocalDateTime appointmentTime;
        try {
            appointmentTime = LocalDateTime.parse(appointmentTimeRaw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ex) {
            model.addAttribute("msg", "Invalid date/time format. Please use the date picker.");
            return "patient_doctors";
        }

        if (appointmentTime.isBefore(LocalDateTime.now())) {
            model.addAttribute("msg", "Cannot book a time in the past.");
            return "patient_doctors";
        }

        Session ss = sf.openSession();
        Transaction tx = null;
        Appointment persisted = null;
        try {
            tx = ss.beginTransaction();

            Patient patient = ss.get(Patient.class, patientId);
            if (patient == null) {
                model.addAttribute("msg", "Patient record not found.");
                if (tx != null) tx.rollback();
                return "home";
            }

            Doctor doc = null;
            if (doctorId != null) {
                doc = ss.get(Doctor.class, doctorId);
                if (doc == null) {
                    model.addAttribute("msg", "Selected doctor not found.");
                    if (tx != null) tx.rollback();
                    return "patient_doctors";
                }

                // Do not allow booking with unapproved doctor
                if (!doc.isApproved()) {
                    model.addAttribute("msg", "You cannot book an appointment with this doctor yet. Doctor is pending admin approval.");
                    if (tx != null) tx.rollback();
                    return "patient_doctors";
                }
            }

            Query<Long> conflictQ = ss.createQuery(
                    "select count(a.id) from Appointment a where a.doctor.id = :did and a.appointmentTime = :t and a.status = :st",
                    Long.class);
            conflictQ.setParameter("did", doctorId);
            conflictQ.setParameter("t", appointmentTime);
            conflictQ.setParameter("st", AppointmentStatus.BOOKED);
            Long conflict = conflictQ.uniqueResult();
            if (conflict != null && conflict > 0) {
                model.addAttribute("msg", "Selected time is already booked. Please choose another time.");
                if (tx != null) tx.rollback();
                return "patient_doctors";
            }

            Appointment ap = new Appointment();
            ap.setPatient(patient);
            ap.setDoctor(doc);
            ap.setAppointmentTime(appointmentTime);
            ap.setNotes(notes != null ? notes : "Booked from patient dashboard");
            ap.setStatus(AppointmentStatus.BOOKED);

            String datePart = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
            String rnd = String.format("%06d", new Random().nextInt(1_000_000));
            ap.setAppointmentNumber("APT-" + datePart + "-" + rnd);

            ss.save(ap);
            tx.commit();
            persisted = ap;

            try {
                if (patient.getEmail() != null && !patient.getEmail().trim().isEmpty()) {
                    boolean sent = emailService.sendAppointmentConfirmation(patient.getEmail(), persisted);
                    if (!sent) {
                        log.warn("Appointment saved but confirmation email failed to send to {}", patient.getEmail());
                    } else {
                        log.info("Confirmation email sent to {}", patient.getEmail());
                    }
                } else {
                    log.info("Patient id {} has no email, skipping confirmation email.", patient.getId());
                }
            } catch (Exception mailEx) {
                log.error("Failed to send appointment confirmation email: {}", mailEx.toString(), mailEx);
            }

            return "redirect:/patient/appointments/confirmation/" + persisted.getId();
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            model.addAttribute("msg", "Error booking appointment: " + ex.getMessage());
            return "patient_appointments";
        } finally {
            ss.close();
        }
    }

    // ---------- cancel appointment ----------
    @PostMapping("/patient/appointments/{id}/cancel")
    public String cancelAppointmentMerged(@PathVariable("id") Long id, Model model, HttpSession session) {
        Object pidObj = session.getAttribute("patientId");
        if (pidObj == null) {
            model.addAttribute("msg", "Please login first.");
            return "home";
        }
        Long patientId = (pidObj instanceof Long) ? (Long) pidObj : Long.parseLong(pidObj.toString());

        Session ss = sf.openSession();
        Transaction tx = null;
        try {
            tx = ss.beginTransaction();
            Appointment ap = ss.get(Appointment.class, id);
            if (ap == null || ap.getPatient() == null || !ap.getPatient().getId().equals(patientId)) {
                model.addAttribute("msg", "Appointment not found or access denied.");
                if (tx != null) tx.rollback();
                return "patient_appointments";
            }

            ap.setStatus(AppointmentStatus.CANCELLED);
            ss.update(ap);
            tx.commit();
            model.addAttribute("msg", "Appointment canceled.");
            return "redirect:/patient/appointments";
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            model.addAttribute("msg", "Error canceling appointment: " + ex.getMessage());
            return "patient_appointments";
        } finally {
            ss.close();
        }
    }

    // ---------- restore canceled appointment ----------
    @PostMapping("/patient/appointments/{id}/restore")
    public String restoreAppointment(@PathVariable("id") Long id, Model model, HttpSession session) {
        Object pidObj = session.getAttribute("patientId");
        if (pidObj == null) {
            model.addAttribute("msg", "Please login first.");
            return "home";
        }
        Long patientId = (pidObj instanceof Long) ? (Long) pidObj : Long.parseLong(pidObj.toString());

        Session ss = sf.openSession();
        Transaction tx = null;
        try {
            tx = ss.beginTransaction();
            Appointment ap = ss.get(Appointment.class, id);
            if (ap == null || ap.getPatient() == null || !ap.getPatient().getId().equals(patientId)) {
                model.addAttribute("msg", "Appointment not found or access denied.");
                if (tx != null) tx.rollback();
                return "patient_appointments";
            }

            ap.setStatus(AppointmentStatus.BOOKED);
            ss.update(ap);
            tx.commit();
            model.addAttribute("msg", "Appointment restored.");
            return "redirect:/patient/appointments";
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            model.addAttribute("msg", "Error restoring appointment: " + ex.getMessage());
            return "patient_appointments";
        } finally {
            ss.close();
        }
    }

    // ---------- permanently delete appointment ----------
    @PostMapping("/patient/appointments/{id}/delete")
    public String deleteAppointment(@PathVariable("id") Long id, Model model, HttpSession session) {
        Object pidObj = session.getAttribute("patientId");
        if (pidObj == null) {
            model.addAttribute("msg", "Please login first.");
            return "home";
        }
        Long patientId = (pidObj instanceof Long) ? (Long) pidObj : Long.parseLong(pidObj.toString());

        Session ss = sf.openSession();
        Transaction tx = null;
        try {
            tx = ss.beginTransaction();
            Appointment ap = ss.get(Appointment.class, id);
            if (ap == null || ap.getPatient() == null || !ap.getPatient().getId().equals(patientId)) {
                model.addAttribute("msg", "Appointment not found or access denied.");
                if (tx != null) tx.rollback();
                return "patient_appointments";
            }

            ss.delete(ap);
            tx.commit();
            model.addAttribute("msg", "Appointment permanently deleted.");
            return "redirect:/patient/appointments";
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            model.addAttribute("msg", "Error deleting appointment: " + ex.getMessage());
            return "patient_appointments";
        } finally {
            ss.close();
        }
    }

    // ---------- logout (patient) ----------
    @GetMapping("/patient/logout")
    public String logoutPatient(HttpSession session) {
        session.invalidate();
        return "home";
    }

    // ---------- small DTO for prescriptions ----------
    public static class PrescriptionRow {
        private final String doctorName;
        private final LocalDate date;
        private final String summary;
        public PrescriptionRow(String doctorName, LocalDate date, String summary) {
            this.doctorName = doctorName;
            this.date = date;
            this.summary = summary;
        }
        public String getDoctorName() { return doctorName; }
        public LocalDate getDate() { return date; }
        public String getSummary() { return summary; }
    }

}



