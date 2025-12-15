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

            // DEBUG (you can comment this in prod)
            System.out.println(
                    "LOGIN DEBUG -> inputUser=" + uname +
                            ", inputPwd=" + password +
                            ", inputRole=" + r +
                            ", dbPwd=" + dblogin.getPassword() +
                            ", dbRole=" + dblogin.getRole()
            );

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

            // 1.5) Email verification check for patient + doctor
            if (("patient".equalsIgnoreCase(r) || "doctor".equalsIgnoreCase(r)) &&
                    (dblogin.getEmailVerified() == null || !dblogin.getEmailVerified())) {
                tx.commit();
                model.addAttribute("msg", "Please verify your email before logging in. Check your inbox.");
                return "login";
            }

            // 2) Based on role
            switch (r) {

                case "patient": {
                    Patient patient;

                    Query<Patient> pq = session.createQuery(
                            "from Patient where lower(trim(email)) = :e",
                            Patient.class
                    );
                    pq.setParameter("e", uname.toLowerCase());
                    patient = pq.uniqueResult();

                    if (patient == null) {
                        // first-time login: create minimal patient
                        patient = new Patient();
                        patient.setEmail(uname);
                        patient.setName(uname);
                        patient.setAge(0);
                        patient.setPhone("");
                        patient.setAddress("");
                        patient.setDisease("");
                        patient.setGender("");

                        session.save(patient);
                    }

                    boolean incomplete = isPatientProfileIncomplete(patient);

                    tx.commit();

                    httpSession.setAttribute("patientId", patient.getId());
                    httpSession.setAttribute("patientName", patient.getName());
                    httpSession.setAttribute("role", "patient");
                    httpSession.setAttribute("username", uname);

                    if (incomplete) {
                        return "redirect:/patient/details";
                    }

                    return "redirect:/patient/dashboard";
                }

                case "doctor": {
                    Doctor doctor = null;

                    // 1️⃣ Try: username matches email OR name
                    Query<Doctor> dq = session.createQuery(
                            "from Doctor where lower(trim(email)) = :u or lower(trim(name)) = :u",
                            Doctor.class
                    );
                    dq.setParameter("u", uname.toLowerCase());
                    doctor = dq.uniqueResult();

                    // 2️⃣ Try: username is doctor ID
                    if (doctor == null) {
                        try {
                            Long did = Long.parseLong(uname);
                            doctor = session.get(Doctor.class, did);
                        } catch (NumberFormatException ignore) {}
                    }

                    // ❌ generic message if no doctor record
                    if (doctor == null) {
                        if (tx != null) tx.rollback();
                        model.addAttribute("msg", INVALID_LOGIN_MSG);
                        return "login";
                    }

                    // 🔒 Approval check
                    if (!doctor.isApproved()) {
                        tx.commit();
                        model.addAttribute("msg", "Your account is pending admin approval. Please wait for approval.");
                        return "login";
                    }

                    boolean incomplete = isDoctorProfileIncomplete(doctor);

                    httpSession.setAttribute("doctorId", doctor.getId());
                    httpSession.setAttribute("doctorName", doctor.getName());
                    httpSession.setAttribute("role", "doctor");
                    httpSession.setAttribute("username", uname);

                    tx.commit();

                    if (incomplete) {
                        // redirect to doctor fill-details page
                        return "redirect:/doctor/details";
                    }

                    return "redirect:/doctor/dashboard";
                }

                case "admin":
                    tx.commit();
                    httpSession.setAttribute("role", "admin");
                    httpSession.setAttribute("username", uname);
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

    // ---------- logout ----------
    @RequestMapping("/logoutPage")
    public String logoutPage(HttpSession session) {
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

            try {
                String encodedUser = URLEncoder.encode(uname, "UTF-8");
                String encodedCode = URLEncoder.encode(code, "UTF-8");
                String verifyLink = "http://localhost:8080/verify?username=" + encodedUser + "&code=" + encodedCode;

                emailService.sendVerificationEmail(uname, verifyLink);
            } catch (Exception mailEx) {
                log.error("Failed to send verification email to {}: {}", uname, mailEx.toString(), mailEx);
            }

            model.addAttribute("msg", "Sign up successful. Please check your email to verify your account before login.");
            return "home";

        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            session.close();
            ex.printStackTrace();
            model.addAttribute("msg", "Error during signup: " + ex.getMessage());
            return "signup";
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
    }

    // ---------- RESET PASSWORD (FORM) ----------
    @GetMapping("/resetPassword")
    public String resetPasswordPage(@RequestParam("username") String username,
                                    @RequestParam("code") String code,
                                    Model model) {

        Session session = sf.openSession();
        try {
            Login login = session.get(Login.class, username.trim());
            if (login == null ||
                    login.getVerificationCode() == null ||
                    !login.getVerificationCode().equals(code)) {

                model.addAttribute("msg", "Invalid or expired reset link.");
                return "home";
            }

            // pass username & code to the form
            model.addAttribute("username", username);
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
            login.setPassword(password.trim());          // (you can hash here)
            login.setVerificationCode(null);             // clear token
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

 // ---------- DOCTOR DETAILS (profile) ----------
    @PostMapping("/doctor/details")
    public String saveDoctorDetails(@RequestParam("id") Long id,
                                    @RequestParam("name") String name,
                                    @RequestParam("specialization") String specialization,
                                    @RequestParam(value = "phone", required = false) String phone,
                                    @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
                                    HttpSession httpSession,
                                    Model model) {

        Object didObj = httpSession.getAttribute("doctorId");
        if (didObj == null) {
            model.addAttribute("msg", "Please login as doctor first.");
            return "home";
        }

        Long sessionDoctorId;
        if (didObj instanceof Long) sessionDoctorId = (Long) didObj;
        else if (didObj instanceof Integer) sessionDoctorId = ((Integer) didObj).longValue();
        else sessionDoctorId = Long.parseLong(didObj.toString());

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

            d.setName(name != null ? name.trim() : d.getName());
            d.setSpecialization(specialization != null ? specialization.trim() : d.getSpecialization());
            if (phone != null) {
                d.setPhone(phone.trim());
            }

            //  handle profile image upload (ABSOLUTE PATH)
            if (imageFile != null && !imageFile.isEmpty()) {
                try {
                    // 1) Absolute project directory
                    String projectDir = System.getProperty("user.dir");
                    // e.g. C:\Users\Pankaj\Desktop\HospitalManagmentSystem
                    // 2) Absolute uploads/doctors directory
                    Path uploadRoot = Paths.get(projectDir, DOCTOR_UPLOAD_DIR);


                    // ensure directory exists
                    if (!Files.exists(uploadRoot)) {
                        Files.createDirectories(uploadRoot);
                    }

                    String originalName = imageFile.getOriginalFilename();
                    String ext = ".png";
                    if (originalName != null && originalName.contains(".")) {
                        ext = originalName.substring(originalName.lastIndexOf("."));
                    }

                    String fileName = "doctor_" + d.getId() + ext;
                    Path filePath = uploadRoot.resolve(fileName);

                    // extra safety: ensure parent exists
                    if (!Files.exists(filePath.getParent())) {
                        Files.createDirectories(filePath.getParent());
                    }

                    // ❗ Now this is an ABSOLUTE path
                    imageFile.transferTo(filePath.toFile());

                    // URL that browser will use (served via WebConfig)
                    String webPath = "/" + DOCTOR_UPLOAD_DIR + "/" + fileName;
                    d.setPhotoPath(webPath);

                    log.info("Doctor {} image saved at {}", d.getId(), filePath.toAbsolutePath());

                } catch (IOException ioEx) {
                    log.error("Error saving doctor image", ioEx);
                    model.addAttribute("msg", "Details saved but image upload failed: " + ioEx.getMessage());
                }
            }

            session.update(d);
            tx.commit();

            httpSession.setAttribute("doctorName", d.getName());

            return "redirect:/doctor/dashboard";

        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            ex.printStackTrace();
            model.addAttribute("msg", "Error saving doctor details: " + ex.getMessage());
            return "fill_doctor_details";
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

    @RequestMapping("contactPage")
    public String contact() { return "contact"; }

    @RequestMapping("/contact")
    public String contactSubmit(@ModelAttribute contact contact, Model model) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        session.save(contact);
        tx.commit();
        session.close();
        return "contact";
    }

    // ---------- ADMIN: view "Get in Touch" submissions ----------
    @GetMapping("/admin/contacts")
    public String viewContactSubmissions(HttpSession httpSession, Model model) {
        // Check admin login
        Object roleObj = httpSession.getAttribute("role");
        if (roleObj == null || !"admin".equalsIgnoreCase(roleObj.toString())) {
            model.addAttribute("msg", "Please login as admin to view this page.");
            return "home";
        }

        Session session = sf.openSession();
        try {
            Query<contact> q = session.createQuery("from contact order by id desc", contact.class);
            List<contact> contacts = q.list();
            model.addAttribute("contacts", contacts);
            return "admin_contacts_details";   // Thymeleaf page
        } finally {
            session.close();
        }
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
