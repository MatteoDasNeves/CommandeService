package pharmacie.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.ConstraintViolationException;
import pharmacie.dao.CommandeRepository;
import pharmacie.dao.LigneRepository;
import pharmacie.dao.MedicamentRepository;
import pharmacie.entity.Ligne;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional // Pour que les modifications soient annulées après chaque test
class CommandeServiceTest {

    @Autowired
    private CommandeService service;

    @Autowired
    private CommandeRepository commandeDao;

    @Autowired
    private MedicamentRepository medicamentDao;

    @Autowired
    private LigneRepository ligneDao;

    // --- Tests pour ajouterLigne ---

    private static final int COMMANDE_NON_ENVOYEE = 99998;
    private static final int COMMANDE_ENVOYEE = 99999;
    private static final int MEDICAMENT_DISPONIBLE = 93;
    private static final int MEDICAMENT_INDISPONIBLE = 97;
    private static final int MEDICAMENT_STOCK_INSUFFISANT = 98; // Stock: 26, Commandées: 20

    @Test
    void testAjouterLigneCommandeEnvoyee() {
        assertThrows(IllegalStateException.class,
                () -> service.ajouterLigne(COMMANDE_ENVOYEE, MEDICAMENT_DISPONIBLE, 1),
                "On ne peut pas ajouter de ligne à une commande déjà envoyée");
    }

    @Test
    void testAjouterLigneMedicamentIndisponible() {
        assertThrows(IllegalStateException.class,
                () -> service.ajouterLigne(COMMANDE_NON_ENVOYEE, MEDICAMENT_INDISPONIBLE, 1),
                "On ne peut pas commander un médicament indisponible");
    }

    @Test
    void testAjouterLigneStockInsuffisant() {
        // Stock 26, Commandées 20. On peut commander au max 6.
        assertThrows(IllegalStateException.class,
                () -> service.ajouterLigne(COMMANDE_NON_ENVOYEE, MEDICAMENT_STOCK_INSUFFISANT, 7),
                "La quantité en stock est insuffisante");
    }

    @Test
    void testAjouterLigneQuantiteNegative() {
        assertThrows(ConstraintViolationException.class,
                () -> service.ajouterLigne(COMMANDE_NON_ENVOYEE, MEDICAMENT_DISPONIBLE, -1),
                "La quantité doit être positive");
    }

    @Test
    void testAjouterLigneExistante() {
        var medicament = medicamentDao.findById(MEDICAMENT_STOCK_INSUFFISANT).orElseThrow();
        var ligneAvant = ligneDao.findByCommandeAndMedicament(commandeDao.findById(COMMANDE_NON_ENVOYEE).orElseThrow(), medicament).orElseThrow();
        int quantiteAvant = ligneAvant.getQuantite();
        int unitesCommandeesAvant = medicament.getUnitesCommandees();

        service.ajouterLigne(COMMANDE_NON_ENVOYEE, MEDICAMENT_STOCK_INSUFFISANT, 1);

        var ligneApres = ligneDao.findById(ligneAvant.getId()).orElseThrow();
        assertEquals(quantiteAvant + 1, ligneApres.getQuantite(), "La quantité de la ligne doit être incrémentée");

        var medicamentApres = medicamentDao.findById(MEDICAMENT_STOCK_INSUFFISANT).orElseThrow();
        assertEquals(unitesCommandeesAvant + 1, medicamentApres.getUnitesCommandees(), "Les unités commandées doivent être mises à jour");
    }

    @Test
    void testAjouterNouvelleLigne() {
        var medicament = medicamentDao.findById(MEDICAMENT_DISPONIBLE).orElseThrow();
        int unitesCommandeesAvant = medicament.getUnitesCommandees();
        long nbLignesAvant = ligneDao.findByCommandeNumero(COMMANDE_NON_ENVOYEE).size();

        service.ajouterLigne(COMMANDE_NON_ENVOYEE, MEDICAMENT_DISPONIBLE, 5);

        var commande = commandeDao.findById(COMMANDE_NON_ENVOYEE).orElseThrow();
        assertEquals(nbLignesAvant + 1, commande.getLignes().size(), "Une nouvelle ligne doit être ajoutée");

        var medicamentApres = medicamentDao.findById(MEDICAMENT_DISPONIBLE).orElseThrow();
        assertEquals(unitesCommandeesAvant + 5, medicamentApres.getUnitesCommandees(), "Les unités commandées doivent être mises à jour");
    }

    // --- Tests pour supprimerLigne ---

    @Test
    void testSupprimerLigneCommandeEnvoyee() {
        var ligne = ligneDao.findByCommandeNumero(COMMANDE_ENVOYEE).get(0);
        assertThrows(IllegalStateException.class, () -> service.supprimerLigne(ligne.getId()),
                "On ne peut pas supprimer la ligne d'une commande envoyée");
    }

    @Test
    void testSupprimerLigneInexistante() {
        assertThrows(NoSuchElementException.class, () -> service.supprimerLigne(0));
    }

    @Test
    void testSupprimerLigne() {
        var ligne = ligneDao.findByCommandeNumero(COMMANDE_NON_ENVOYEE).get(0);
        var medicament = ligne.getMedicament();
        int quantiteLigne = ligne.getQuantite();
        int unitesCommandeesAvant = medicament.getUnitesCommandees();
        long nbLignesAvant = ligneDao.findByCommandeNumero(COMMANDE_NON_ENVOYEE).size();

        service.supprimerLigne(ligne.getId());

        assertEquals(nbLignesAvant - 1, ligneDao.findByCommandeNumero(COMMANDE_NON_ENVOYEE).size(), "La ligne doit être supprimée");

        var medicamentApres = medicamentDao.findById(medicament.getReference()).orElseThrow();
        assertEquals(unitesCommandeesAvant - quantiteLigne, medicamentApres.getUnitesCommandees(), "Les unités commandées doivent être décrémentées");
    }

    // --- Tests pour enregistreExpedition ---

    @Test
    void testEnregistrerExpeditionCommandeInexistante() {
        assertThrows(NoSuchElementException.class, () -> service.enregistreExpedition(0));
    }

    @Test
    void testEnregistrerExpeditionCommandeDejaEnvoyee() {
        assertThrows(IllegalStateException.class, () -> service.enregistreExpedition(COMMANDE_ENVOYEE),
                "On ne peut pas expédier une commande déjà envoyée");
    }

    @Test
    void testEnregistrerExpedition() {
        var commande = service.getCommande(COMMANDE_NON_ENVOYEE);
        Ligne ligne = commande.getLignes().get(0);
        var medicament = ligne.getMedicament();
        int quantite = ligne.getQuantite();
        int stockAvant = medicament.getUnitesEnStock();
        int commandeesAvant = medicament.getUnitesCommandees();

        var commandeExpediee = service.enregistreExpedition(COMMANDE_NON_ENVOYEE);

        assertNotNull(commandeExpediee.getEnvoyeele(), "La date d'expédition doit être renseignée");

        var medicamentApres = medicamentDao.findById(medicament.getReference()).orElseThrow();
        assertEquals(stockAvant - quantite, medicamentApres.getUnitesEnStock(), "Le stock doit être décrémenté");
        assertEquals(commandeesAvant - quantite, medicamentApres.getUnitesCommandees(), "Les unités commandées doivent être décrémentées");
    }
}
