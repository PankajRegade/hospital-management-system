package HMS.example.HospitalManagementSystem.controller;

import org.hibernate.*;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import HMS.example.HospitalManagementSystem.model.*;
import HMS.example.HospitalManagementSystem.service.EmailService;
import jakarta.servlet.http.HttpSession;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

@Controller
@RequestMapping("/appointments")
public class AppointmentController {

    private static final Logger log = LoggerFactory.getLogger(AppointmentController.class);

    @Autowired private SessionFactory sf;
    @Autowired private EmailService emailService;

    private Long getID(HttpSession session, String key){
        Object v = session.getAttribute(key);
        if(v==null) return null;
        if(v instanceof Number) return ((Number)v).longValue();
        return Long.valueOf(v.toString());
    }

    /*-----------------------------------------------------
     🟢 BOOK APPOINTMENT (Patient)
     -----------------------------------------------------*/
    @PostMapping("/book")
    public String book(@RequestParam Long doctorId,
                       @RequestParam String timeRaw,
                       Model model, HttpSession session){

        Long pid = getID(session,"patientId");
        if(pid==null) return msg(model,"Login required","home");

        LocalDateTime dt;
        try { dt = LocalDateTime.parse(timeRaw); }
        catch(Exception e){ return msg(model,"Pick valid date/time","patient_doctors"); }

        if(dt.isBefore(LocalDateTime.now()))
            return msg(model,"Cannot pick past time","patient_doctors");

        Session ss = sf.openSession(); Transaction tx = ss.beginTransaction();
        try{
            Patient p = ss.get(Patient.class,pid);
            Doctor d = ss.get(Doctor.class,doctorId);
            if(p==null||d==null) return msg(model,"Doctor/Patient missing","home");

            Long clash = ss.createQuery("""
                select count(a.id) from Appointment a 
                where a.doctor.id=:d and a.appointmentTime=:t and a.status='BOOKED'
            """,Long.class)
            .setParameter("d",doctorId)
            .setParameter("t",dt)
            .uniqueResult();

            if(clash!=null && clash>0)
                return msg(model,"Time slot unavailable","patient_doctors");

            Appointment a = new Appointment();
            a.setAppointmentTime(dt);
            a.setDoctor(d);
            a.setPatient(p);
            a.setStatus(AppointmentStatus.BOOKED);
            a.setNotes("Booked via system");

            a.setAppointmentNumber("APT-"+LocalDate.now()+"-"+new Random().nextInt(9999));
            ss.save(a); tx.commit();

            if(p.getEmail()!=null)
                try{ emailService.sendAppointmentConfirmation(p.getEmail(),a);}catch(Exception ignore){}

            return "redirect:/appointments/confirmation/"+a.getId();

        }catch(Exception e){
            tx.rollback();
            return msg(model,"Booking Failed: "+e.getMessage(),"patient_doctors");
        }finally{ ss.close(); }
    }

    /*-----------------------------------------------------
     🟡 CONFIRMATION PAGE (After Booking)
     -----------------------------------------------------*/
    @GetMapping("/confirmation/{id}")
    public String confirmed(@PathVariable Long id,Model model,HttpSession session){
        Long pid=getID(session,"patientId");
        Appointment a=fetch(id);
        if(a==null||pid==null||!pid.equals(a.getPatient().getId()))
            return msg(model,"Access denied","home");
        model.addAttribute("appointment",a);
        return "appointment_confirmation";
    }

    /*-----------------------------------------------------
     🟠 PATIENT — List & View
     -----------------------------------------------------*/
    @GetMapping("/patient")
    public String myAppointments(Model model,HttpSession s){
        Long pid=getID(s,"patientId");
        if(pid==null) return msg(model,"Login needed","home");

        Session ss=sf.openSession();
        List<Appointment> list=ss.createQuery(
            "from Appointment a where a.patient.id=:p order by a.appointmentTime desc",
            Appointment.class).setParameter("p",pid).list();
        ss.close();

        model.addAttribute("appointments",list);
        model.addAttribute("patientName",s.getAttribute("patientName"));
        return "patient_appointments";
    }

    /*-----------------------------------------------------
     🟣 DOCTOR — List All Appointments (Dashboard)
     -----------------------------------------------------*/
    @GetMapping("/doctor")
    public String doctorList(Model model,HttpSession s){
        Long did=getID(s,"doctorId");
        if(did==null) return msg(model,"Login Doctor","home");

        Session ss=sf.openSession();
        List<Appointment> list=ss.createQuery(
            "from Appointment a where a.doctor.id=:d order by a.appointmentTime desc",
            Appointment.class).setParameter("d",did).list();
        ss.close();

        model.addAttribute("appointments",list);
        model.addAttribute("doctorName",s.getAttribute("doctorName"));
        return "doctor_dashboard";
    }

    /*-----------------------------------------------------
     🔥 VIEW DETAILS — DOCTOR + PATIENT
     -----------------------------------------------------*/
    @GetMapping("/{id}")
    public String details(@PathVariable Long id, Model m,HttpSession s){
        Appointment a=fetch(id);
        Long pid=getID(s,"patientId");
        Long did=getID(s,"doctorId");

        if(a==null) return msg(m,"Not Found","home");

        if(pid!=null && a.getPatient().getId().equals(pid)){
            m.addAttribute("appointment",a);
            return "patient_appointment_details";
        }
        if(did!=null && a.getDoctor().getId().equals(did)){
            m.addAttribute("appointment",a);
            return "doctor_appointment_details";
        }
        return msg(m,"Unauthorized","home");
    }

    /*-----------------------------------------------------
     ✏ EDIT APPOINTMENT — DOCTOR
     -----------------------------------------------------*/
    @GetMapping("/doctor/{id}/edit")
    public String edit(@PathVariable Long id, Model m,HttpSession s){
        Long did=getID(s,"doctorId");
        Appointment a=fetch(id);

        if(a==null||did==null||!did.equals(a.getDoctor().getId()))
            return msg(m,"Not allowed","doctor_dashboard");

        m.addAttribute("appointment",a);
        return "doctor_appointment_edit";
    }

    /*-----------------------------------------------------
     💾 UPDATE — DOCTOR
     -----------------------------------------------------*/
    @PostMapping("/doctor/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam("date") String date,
                         @RequestParam("time") String time,
                         @RequestParam AppointmentStatus status,
                         Model m,HttpSession s){

        Long did = getID(s,"doctorId");
        Session ss = sf.openSession(); Transaction tx = ss.beginTransaction();

        try {
            Appointment a = ss.get(Appointment.class,id);

            if(a == null || did == null || !did.equals(a.getDoctor().getId()))
                return msg(m,"Unauthorized","doctor_dashboard");

            // ⬇⯈ Combine date + time into LocalDateTime
            LocalDateTime dt = LocalDateTime.parse(date + "T" + time);

            a.setAppointmentTime(dt); // ⬅ correct field
            a.setStatus(status);

            ss.update(a);
            tx.commit();
            return "redirect:/appointments/" + id;

        } catch(Exception e) {
            tx.rollback();
            return msg(m,"Update Failed: "+e.getMessage(),"doctor_appointment_edit");
        } finally { ss.close(); }
    }
    /*-----------------------------------------------------
     ❗ CANCEL Appointment (D+P Allowed)
     -----------------------------------------------------*/
    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id,HttpSession s){
        Long did=getID(s,"doctorId"), pid=getID(s,"patientId");
        Session ss=sf.openSession(); Transaction tx=ss.beginTransaction();

        Appointment a=ss.get(Appointment.class,id);
        if(a!=null && ((did!=null && did.equals(a.getDoctor().getId())) ||
                       (pid!=null && pid.equals(a.getPatient().getId())))){
            a.setStatus(AppointmentStatus.CANCELLED);
            ss.update(a); tx.commit();
        }else tx.rollback();

        ss.close();
        return (did!=null) ? "redirect:/appointments/doctor"
                           : "redirect:/appointments/patient";
    }

    
    // UTILITIES
    private Appointment fetch(Long id){
        Session s=sf.openSession(); Appointment a=s.get(Appointment.class,id); s.close(); return a;
    }
    private String msg(Model m,String t,String page){ m.addAttribute("msg",t); return page; }
}