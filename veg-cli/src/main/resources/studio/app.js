(() => {
  "use strict";

  const DIAGRAM_SYSTEM_TYPES = new Set([
    "StudioDiagramView",
    "StudioDiagramTable",
    "StudioDiagramEdge",
    "StudioDiagramNote"
  ]);

  const state = {
    overview: null,
    history: null,
    activeType: null,
    sortBy: "_id",
    sortDir: "asc",
    offset: 0,
    limit: 100,
    globalFilter: "",
    colFilters: {},
    selectedEntityId: null,
    datasheet: null,
    branch: "",
    asOf: "",
    catalogFilter: "",
    diagramViews: [],
    activeDiagramId: null,
    diagramSelection: null,
    diagramDirty: false,
    diagramLinkingNoteId: null
  };

  const el = (tag, props = {}, children = []) => {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(props)) {
      if (k === "className") node.className = v;
      else if (k === "textContent") node.textContent = v;
      else if (k.startsWith("on") && typeof v === "function") node.addEventListener(k.slice(2).toLowerCase(), v);
      else if (v !== undefined && v !== null) node.setAttribute(k, v);
    }
    for (const child of Array.isArray(children) ? children : [children]) {
      if (typeof child === "string") node.appendChild(document.createTextNode(child));
      else if (child) node.appendChild(child);
    }
    return node;
  };

  const toast = (message, type = "ok") => {
    const area = document.getElementById("toastArea");
    const node = el("div", { className: `toast ${type}`, textContent: message });
    area.appendChild(node);
    setTimeout(() => node.remove(), 4200);
  };

  const apiGet = async (path, params = {}) => {
    const url = new URL(path, window.location.origin);
    if (state.branch) url.searchParams.set("branch", state.branch);
    if (state.asOf) url.searchParams.set("asOf", state.asOf);
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null && v !== "") url.searchParams.set(k, v);
    }
    const resp = await fetch(url);
    const data = await resp.json();
    if (!resp.ok) throw new Error(data.message || `HTTP ${resp.status}`);
    return data;
  };

  const apiPost = async (path, body = {}) => {
    const actor = document.getElementById("actorInput").value.trim() || "studio-user";
    const resp = await fetch(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ branch: state.branch, actor, ...body })
    });
    const data = await resp.json();
    if (!resp.ok) throw new Error(data.message || `HTTP ${resp.status}`);
    return data;
  };

  const debounce = (fn, ms = 220) => {
    let t;
    return (...args) => {
      clearTimeout(t);
      t = setTimeout(() => fn(...args), ms);
    };
  };

  const switchView = (viewName) => {
    document.querySelectorAll(".tab-btn").forEach((b) => {
      b.classList.toggle("active", b.dataset.view === viewName);
    });
    document.querySelectorAll(".view-panel").forEach((p) => {
      p.classList.toggle("active", p.id === `view-${viewName}`);
    });
    if (viewName === "schema") renderSchemaView();
    if (viewName === "diagram") renderDiagramView();
    if (viewName === "history") loadHistory();
  };

  const loadAll = async () => {
    try {
      const [overview, history, diagData] = await Promise.all([
        apiGet("/api/overview"),
        apiGet("/api/history"),
        apiGet("/api/diagrams")
      ]);
      state.overview = overview;
      state.history = history;
      if (!state.branch) state.branch = overview.activeBranch;
      syncDiagramViewsFromServer(diagData);
      renderTopContext();
      renderCatalog();
      renderSchemaSelectors();
      renderQueryPresets();

      if (!state.activeType && overview.objectTypes.length > 0) {
        const preferred = overview.objectTypes.find((t) => t.name === "Invariant")
          || overview.objectTypes.find((t) => !DIAGRAM_SYSTEM_TYPES.has(t.name) && t.count > 0)
          || overview.objectTypes[0];
        state.activeType = preferred.name;
      }
      if (state.activeType) {
        await loadDatasheet();
      }
      if (state.selectedEntityId) {
        await loadEntityDetail(state.selectedEntityId, true);
      }
      if (document.getElementById("view-schema").classList.contains("active")) {
        renderSchemaView();
      }
      if (document.getElementById("view-diagram").classList.contains("active")) {
        renderDiagramView();
      }
      if (document.getElementById("view-history").classList.contains("active")) {
        renderHistoryView();
      }
    } catch (err) {
      toast(err.message, "error");
    }
  };

  const renderTopContext = () => {
    const ov = state.overview;
    document.getElementById("dbStatsLine").textContent =
      `${ov.entityCount} Entities · ${ov.edgeCount} Edges · Branch ${ov.activeBranch} · Commit ${ov.commitId}`;

    const branchSel = document.getElementById("branchSelect");
    branchSel.innerHTML = "";
    ov.branches.forEach((b) => {
      const opt = el("option", { value: b.name, textContent: `${b.name} (${b.headCommitId})` });
      if (b.name === ov.activeBranch) opt.selected = true;
      branchSel.appendChild(opt);
    });

    const asOfSel = document.getElementById("asOfSelect");
    asOfSel.innerHTML = "";
    asOfSel.appendChild(el("option", { value: "", textContent: `HEAD Live (${ov.headCommitId})` }));
    if (state.history) {
      [...state.history.commits].reverse().forEach((c) => {
        const label = `${c.id} · ${c.actor} · ${c.reason}`.slice(0, 62);
        const opt = el("option", { value: c.id, textContent: label });
        if (state.asOf === c.id) opt.selected = true;
        asOfSel.appendChild(opt);
      });
    }

    const banner = document.getElementById("historicalBanner");
    banner.classList.toggle("hidden", !ov.historical);
    if (ov.historical) {
      document.getElementById("historicalCommitLabel").textContent = ov.commitId;
    }
    document.getElementById("newRecordBtn").disabled = ov.historical;
  };

  const renderCatalog = () => {
    const ov = state.overview;
    const filter = state.catalogFilter.toLowerCase();

    const objUl = document.getElementById("objectTypesList");
    objUl.innerHTML = "";
    ov.objectTypes
      .filter((t) => !filter || t.name.toLowerCase().includes(filter))
      .forEach((t) => {
        const item = el("li", {
          className: `catalog-item ${state.activeType === t.name ? "active" : ""}`,
          onClick: () => selectType(t.name)
        }, [
          el("span", { className: "name", textContent: t.name }),
          el("span", { className: "badge", textContent: String(t.count) })
        ]);
        objUl.appendChild(item);
      });

    const valUl = document.getElementById("valueTypesList");
    valUl.innerHTML = "";
    ov.valueTypes
      .filter((t) => !filter || t.name.toLowerCase().includes(filter))
      .forEach((t) => {
        const item = el("li", {
          className: `catalog-item ${state.activeType === t.name ? "active" : ""}`,
          onClick: () => selectType(t.name)
        }, [
          el("span", { className: "name", textContent: `${t.name} (${t.scalarType})` }),
          el("span", { className: "badge", textContent: String(t.count) })
        ]);
        valUl.appendChild(item);
      });

    const predUl = document.getElementById("predicatesList");
    predUl.innerHTML = "";
    ov.predicates
      .filter((p) => !filter || p.name.toLowerCase().includes(filter))
      .forEach((p) => {
        predUl.appendChild(el("li", { className: "catalog-item" }, [
          el("span", { className: "name", textContent: `${p.name} (${p.count})` }),
          el("span", { className: "pred-sig", textContent: `${p.domainName} → ${p.rangeName}` })
        ]));
      });
  };

  const selectType = async (typeName) => {
    state.activeType = typeName;
    state.offset = 0;
    state.sortBy = "_id";
    state.sortDir = "asc";
    state.colFilters = {};
    state.globalFilter = "";
    document.getElementById("globalSearchInput").value = "";
    renderCatalog();
    switchView("datasheet");
    await loadDatasheet();
  };

  const loadDatasheet = async () => {
    if (!state.activeType) return;
    const params = {
      type: state.activeType,
      sortBy: state.sortBy,
      sortDir: state.sortDir,
      limit: state.limit,
      offset: state.offset,
      q: state.globalFilter
    };
    for (const [k, v] of Object.entries(state.colFilters)) {
      if (v) params[`filter.${k}`] = v;
    }
    const ds = await apiGet("/api/datasheet", params);
    state.datasheet = ds;
    renderDatasheet();
  };

  const renderDatasheet = () => {
    const ds = state.datasheet;
    document.getElementById("activeTypeTitle").textContent = ds.typeName;
    document.getElementById("activeTypeMeta").textContent =
      `${ds.typeKind === "VALUE_TYPE" ? "Werttyp" : "Objekttyp"} · ${ds.filteredCount} / ${ds.totalCount} Datensätze`;

    const thead = document.getElementById("datasheetHead");
    const sig = `${ds.typeName}|${ds.columns.map((c) => c.name).join(",")}`;

    if (thead.dataset.sig !== sig) {
      thead.dataset.sig = sig;
      thead.innerHTML = "";
      const headerRow = el("tr");
      const filterRow = el("tr", { className: "filter-row" });

      const addHeader = (key, label, subtitle) => {
        const labelSpan = el("span", { "data-sort-label": key, textContent: label });
        const th = el("th", {
          className: "th-sort",
          onClick: () => {
            if (state.sortBy === key) state.sortDir = state.sortDir === "asc" ? "desc" : "asc";
            else { state.sortBy = key; state.sortDir = "asc"; }
            loadDatasheet();
          }
        }, [
          labelSpan,
          subtitle ? el("span", { className: "col-kind-badge", textContent: subtitle }) : null
        ]);
        headerRow.appendChild(th);

        const input = el("input", {
          className: "filter-input",
          "data-filter-col": key,
          placeholder: "Filter...",
          value: state.colFilters[key] || "",
          onInput: debounce((ev) => {
            state.colFilters[key] = ev.target.value;
            state.offset = 0;
            loadDatasheet();
          }, 240)
        });
        filterRow.appendChild(el("th", {}, input));
      };

      addHeader("_id", "ID", "EntityId");
      ds.columns.forEach((col) => {
        const sub = col.kind === "ATTRIBUTE" ? (col.scalarType || col.rangeType) : `→ ${col.rangeType}`;
        addHeader(col.name, col.name, sub);
      });

      thead.appendChild(headerRow);
      thead.appendChild(filterRow);
    }

    thead.querySelectorAll("[data-sort-label]").forEach((span) => {
      const key = span.getAttribute("data-sort-label");
      const base = key === "_id" ? "ID" : key;
      const arrow = state.sortBy === key ? (state.sortDir === "asc" ? " ▲" : " ▼") : "";
      span.textContent = base + arrow;
    });
    thead.querySelectorAll("[data-filter-col]").forEach((inp) => {
      const key = inp.getAttribute("data-filter-col");
      const wanted = state.colFilters[key] || "";
      if (document.activeElement !== inp && inp.value !== wanted) {
        inp.value = wanted;
      }
    });

    const tbody = document.getElementById("datasheetBody");
    tbody.innerHTML = "";

    if (ds.rows.length === 0) {
      tbody.appendChild(el("tr", {}, el("td", {
        colSpan: ds.columns.length + 1,
        className: "muted",
        textContent: "Keine passenden Datensätze gefunden."
      })));
    }

    ds.rows.forEach((row) => {
      const tr = el("tr", {
        className: state.selectedEntityId === row._id ? "selected" : "",
        onClick: () => selectRow(row._id)
      });
      tr.appendChild(el("td", { className: "cell-id", textContent: `#${row._id}` }));

      ds.columns.forEach((col) => {
        const val = row._cells[col.name] ?? "";
        const td = el("td", {
          className: `cell-truncate ${col.editable ? "cell-editable" : ""}`,
          title: col.editable ? "Doppelklick zum Bearbeiten: " + val : val,
          textContent: val
        });
        if (col.editable) {
          td.addEventListener("dblclick", (ev) => {
            ev.stopPropagation();
            beginInlineEdit(td, row._id, col.name, val);
          });
        }
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    });

    const pag = document.getElementById("paginationBar");
    pag.innerHTML = "";
    const start = ds.filteredCount === 0 ? 0 : ds.offset + 1;
    const end = Math.min(ds.offset + ds.rows.length, ds.filteredCount);
    pag.appendChild(el("span", { textContent: `Zeige ${start}–${end} von ${ds.filteredCount} Treffern` }));

    const nav = el("div", { style: "display:flex;gap:6px;" }, [
      el("button", {
        className: "btn btn-sm btn-secondary",
        textContent: "Zurück",
        disabled: ds.offset <= 0 ? "true" : null,
        onClick: () => { state.offset = Math.max(0, state.offset - state.limit); loadDatasheet(); }
      }),
      el("button", {
        className: "btn btn-sm btn-secondary",
        textContent: "Weiter",
        disabled: ds.offset + ds.limit >= ds.filteredCount ? "true" : null,
        onClick: () => { state.offset += state.limit; loadDatasheet(); }
      })
    ]);
    pag.appendChild(nav);
  };

  const beginInlineEdit = (td, entityId, predicate, currentValue) => {
    if (state.overview?.historical) return;
    const input = el("input", { className: "inline-edit-input", value: currentValue });
    td.textContent = "";
    td.appendChild(input);
    input.focus();
    input.select();

    let committed = false;
    const finish = async (save) => {
      if (committed) return;
      committed = true;
      if (!save || input.value === currentValue) {
        td.textContent = currentValue;
        return;
      }
      try {
        const res = await apiPost("/api/cell", {
          entityId: String(entityId),
          predicate,
          value: input.value,
          reason: `Studio: Edit ${predicate} on #${entityId}`
        });
        toast(`Gespeichert in Commit ${res.commitId}`);
        await loadAll();
      } catch (err) {
        toast(err.message, "error");
        td.textContent = currentValue;
      }
    };

    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter") finish(true);
      if (e.key === "Escape") finish(false);
    });
    input.addEventListener("blur", () => finish(true));
  };

  const selectRow = async (entityId) => {
    state.selectedEntityId = entityId;
    document.querySelectorAll("#datasheetBody tr").forEach((tr) => {
      const idCell = tr.firstElementChild?.textContent;
      tr.classList.toggle("selected", idCell === `#${entityId}`);
    });
    await loadEntityDetail(entityId, false);
  };

  const loadEntityDetail = async (entityId, silentOnMissing) => {
    try {
      const detail = await apiGet("/api/entity", { id: entityId });
      renderEntityDrawer(detail);
    } catch (err) {
      if (!silentOnMissing) toast(err.message, "error");
      document.getElementById("drawerEmpty").classList.remove("hidden");
      document.getElementById("drawerContent").classList.add("hidden");
    }
  };

  const renderEntityDrawer = (detail) => {
    document.getElementById("drawerEmpty").classList.add("hidden");
    const container = document.getElementById("drawerContent");
    container.classList.remove("hidden");
    container.innerHTML = "";

    container.appendChild(el("div", { className: "drawer-header" }, [
      el("div", { className: "pill", textContent: `${detail.typeName} · #${detail.id} · ${detail.kind}` }),
      el("h3", { textContent: detail.label })
    ]));

    // Attributes section (editable in place)
    const attrSec = el("div", { className: "drawer-section" }, el("h4", { textContent: "Attribute (Unterdatenblatt)" }));
    if (detail.attributes.length === 0 && detail.kind !== "VALUE") {
      attrSec.appendChild(el("div", { className: "muted", textContent: "Keine skalaren Attribute gesetzt." }));
    }
    detail.attributes.forEach((a) => {
      const inp = el("input", { value: a.value, disabled: detail.historical ? "true" : null });
      const saveBtn = el("button", {
        className: "btn btn-sm btn-secondary",
        textContent: "OK",
        disabled: detail.historical ? "true" : null,
        onClick: async () => {
          try {
            const res = await apiPost("/api/cell", {
              entityId: String(detail.id),
              predicate: a.predicate,
              value: inp.value,
              reason: `Studio: Update ${a.predicate} on #${detail.id}`
            });
            toast(`${a.predicate} → ${res.commitId}`);
            await loadAll();
          } catch (err) { toast(err.message, "error"); }
        }
      });
      attrSec.appendChild(el("div", { className: "attr-row" }, [
        el("div", { className: "attr-name", textContent: a.predicate }),
        el("div", { className: "attr-val" }, inp),
        saveBtn
      ]));
    });
    container.appendChild(attrSec);

    // Outgoing Relations
    const outSec = el("div", { className: "drawer-section" }, el("h4", { textContent: `Ausgehende Relationen (${detail.outgoingRelations.length})` }));
    detail.outgoingRelations.forEach((rel) => {
      outSec.appendChild(el("div", { className: "rel-card" }, [
        el("div", {}, [
          el("div", { className: "muted", style: "font-size:11px", textContent: `${rel.predicate} → ${rel.otherType} (#${rel.otherId})` }),
          el("button", {
            className: "rel-link",
            textContent: rel.otherLabel,
            onClick: () => jumpToEntity(rel.otherId)
          })
        ]),
        !detail.historical ? el("button", {
          className: "btn btn-sm btn-danger",
          textContent: "Trennen",
          onClick: async () => {
            try {
              const res = await apiPost("/api/disconnect", { edgeId: String(rel.edgeId) });
              toast(`Kante #${rel.edgeId} getrennt (${res.commitId})`);
              await loadAll();
            } catch (err) { toast(err.message, "error"); }
          }
        }) : null
      ]));
    });
    container.appendChild(outSec);

    // Connect Relation Quick Box
    if (!detail.historical && detail.kind === "OBJECT" && state.overview) {
      const relPreds = state.overview.predicates.filter((p) =>
        p.rangeKind !== "VALUE_TYPE" && (p.domainName === detail.typeName || p.domainName === "ANY_OBJECT" || p.domainName === "ANY_ENTITY")
      );
      if (relPreds.length > 0) {
        const predSel = el("select");
        relPreds.forEach((p) => predSel.appendChild(el("option", { value: p.name, textContent: `${p.name} → ${p.rangeName}` })));
        const targetSel = el("select");
        const targetManual = el("input", { placeholder: "Oder Ziel-ID z. B. 1025" });

        const refreshTargets = async () => {
          const chosen = relPreds.find((p) => p.name === predSel.value);
          targetSel.innerHTML = "";
          if (chosen && chosen.rangeKind === "OBJECT_TYPE") {
            const ds = await apiGet("/api/datasheet", { type: chosen.rangeName, limit: 150 });
            ds.rows.forEach((r) => targetSel.appendChild(el("option", { value: r._id, textContent: `#${r._id} ${r._label}` })));
          }
        };
        predSel.addEventListener("change", refreshTargets);
        refreshTargets();

        const box = el("div", { className: "drawer-section" }, [
          el("h4", { textContent: "+ Relation verknüpfen" }),
          el("div", { className: "connect-box" }, [
            predSel,
            targetSel,
            targetManual,
            el("button", {
              className: "btn btn-sm btn-primary",
              textContent: "Verbinden",
              onClick: async () => {
                const targetId = targetManual.value.trim() || targetSel.value;
                if (!targetId) { toast("Bitte Ziel-Entity wählen", "error"); return; }
                try {
                  const res = await apiPost("/api/connect", {
                    sourceId: String(detail.id),
                    predicate: predSel.value,
                    targetId
                  });
                  toast(`Verbunden in ${res.commitId}`);
                  await loadAll();
                } catch (err) { toast(err.message, "error"); }
              }
            })
          ])
        ]);
        container.appendChild(box);
      }
    }

    // Incoming Relations
    const inSec = el("div", { className: "drawer-section" }, el("h4", { textContent: `Eingehende Referenzen (${detail.incomingRelations.length})` }));
    if (detail.incomingRelations.length === 0) {
      inSec.appendChild(el("div", { className: "muted", textContent: "Keine eingehenden Referenzen." }));
    }
    detail.incomingRelations.forEach((rel) => {
      inSec.appendChild(el("div", { className: "rel-card" }, [
        el("div", {}, [
          el("div", { className: "muted", style: "font-size:11px", textContent: `← ${rel.predicate} von ${rel.otherType} (#${rel.otherId})` }),
          el("button", {
            className: "rel-link",
            textContent: rel.otherLabel,
            onClick: () => jumpToEntity(rel.otherId)
          })
        ])
      ]));
    });
    container.appendChild(inSec);

    // Entity Commit History
    const histSec = el("div", { className: "drawer-section" }, el("h4", { textContent: `Versionshistorie (${detail.history.length})` }));
    detail.history.forEach((h) => {
      histSec.appendChild(el("div", { className: "rel-card" }, [
        el("div", {}, [
          el("strong", { className: "mono", textContent: h.commitId }),
          document.createTextNode(` · ${h.actor} — ${h.reason}`)
        ]),
        el("button", {
          className: "btn btn-sm btn-secondary",
          textContent: "asOf",
          onClick: () => setTimeTravel(h.commitId)
        })
      ]));
    });
    container.appendChild(histSec);
  };

  const jumpToEntity = async (entityId) => {
    state.selectedEntityId = entityId;
    await loadEntityDetail(entityId, false);
  };

  const renderSchemaView = () => {
    if (!state.overview) return;
    const wrap = document.getElementById("schemaDiagram");
    wrap.innerHTML = "";

    const objTypes = state.overview.objectTypes;
    const relPreds = state.overview.predicates.filter((p) => p.rangeKind === "OBJECT_TYPE");
    const attrPreds = state.overview.predicates.filter((p) => p.rangeKind === "VALUE_TYPE");

    // Visual Access-style relationship cards with clickable table jump
    const grid = el("div", { style: "display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:10px;" });
    objTypes.forEach((t) => {
      const attrs = attrPreds.filter((p) => p.domainName === t.name || p.domainName === "ANY_OBJECT");
      const rels = relPreds.filter((p) => p.domainName === t.name || p.domainName === "ANY_OBJECT");

      const card = el("div", { className: "card", style: "padding:10px;border-top:3px solid var(--accent);" }, [
        el("div", { style: "display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;" }, [
          el("button", { className: "rel-link", textContent: t.name, onClick: () => selectType(t.name) }),
          el("span", { className: "badge", textContent: String(t.count) })
        ]),
        el("div", { style: "font-size:11px;margin-bottom:6px;" },
          attrs.slice(0, 7).map((a) => el("div", { className: "muted", textContent: `• ${a.name}: ${a.rangeName}` }))
        ),
        rels.length > 0 ? el("div", { style: "border-top:1px solid #e7ecf1;padding-top:5px;font-size:11px;" },
          rels.map((r) => el("div", { className: "mono", style: "color:var(--accent)", textContent: `→ ${r.name} → ${r.rangeName} (${r.count})` }))
        ) : null
      ]);
      grid.appendChild(card);
    });
    wrap.appendChild(grid);
  };

  const renderSchemaSelectors = () => {
    if (!state.overview) return;
    const ov = state.overview;

    const domainSel = document.getElementById("predDomainSelect");
    const rangeSel = document.getElementById("predRangeSelect");
    domainSel.innerHTML = "";
    rangeSel.innerHTML = "";

    ["ANY_OBJECT", "ANY_EDGE", "ANY_ENTITY"].forEach((w) => domainSel.appendChild(el("option", { value: w, textContent: w })));
    ov.objectTypes.forEach((t) => domainSel.appendChild(el("option", { value: t.name, textContent: t.name })));

    ov.valueTypes.forEach((v) => rangeSel.appendChild(el("option", { value: v.name, textContent: `${v.name} (${v.scalarType})` })));
    ov.objectTypes.forEach((t) => rangeSel.appendChild(el("option", { value: t.name, textContent: `${t.name} (Objekt)` })));
    ["ANY_OBJECT", "STRING", "INT", "BOOLEAN"].forEach((w) => rangeSel.appendChild(el("option", { value: w, textContent: w })));

    // Query Builder dropdowns
    const qbMatch = document.getElementById("qbMatchType");
    const qbPred = document.getElementById("qbJoinPred");
    const qbTarget = document.getElementById("qbJoinTarget");
    qbMatch.innerHTML = "";
    ov.objectTypes.forEach((t) => qbMatch.appendChild(el("option", { value: t.name, textContent: t.name })));

    qbPred.innerHTML = '<option value="">-- Kein Join --</option>';
    ov.predicates.filter((p) => p.rangeKind === "OBJECT_TYPE").forEach((p) => {
      qbPred.appendChild(el("option", { value: p.name, textContent: `${p.name} (${p.domainName} → ${p.rangeName})` }));
    });

    qbTarget.innerHTML = '<option value="">-- Zieltyp optional --</option>';
    ov.objectTypes.forEach((t) => qbTarget.appendChild(el("option", { value: t.name, textContent: t.name })));
  };

  const renderQueryPresets = () => {
    const presets = [
      {
        label: "ADRs ↔ Invariante I-17",
        dsl: 'MATCH (adr:ArchitectureDecision)\nJOIN (adr)-[addressesInvariant]->(inv:Invariant)\nWHERE inv.id = "I-17"\nSELECT adr.id, adr.title, inv.id, inv.title'
      },
      {
        label: "Modul-Abhängigkeiten",
        dsl: "MATCH (m:Module)\nJOIN (m)-[dependsOnModule]->(dep:Module)\nSELECT m.id, dep.id, dep.category\nORDER BY m.id ASC"
      },
      {
        label: "Module pro Stufe",
        dsl: "MATCH (m:Module)\nJOIN (m)-[implementsStage]->(s:Stage)\nSELECT m.id, s.id, s.title, s.status\nORDER BY s.stageNumber ASC"
      },
      {
        label: "WAL-Invarianten",
        dsl: 'MATCH (i:Invariant)\nWHERE i.summary CONTAINS "WAL"\nSELECT i.id, i.title, i.category'
      }
    ];
    const row = document.getElementById("queryPresets");
    row.innerHTML = "";
    presets.forEach((p) => {
      row.appendChild(el("button", {
        className: "btn btn-sm btn-secondary",
        type: "button",
        textContent: p.label,
        onClick: () => {
          document.getElementById("dslTextarea").value = p.dsl;
          runDslQuery();
        }
      }));
    });
  };

  const buildDslFromUi = () => {
    const type = document.getElementById("qbMatchType").value || "Invariant";
    const joinPred = document.getElementById("qbJoinPred").value;
    const joinTarget = document.getElementById("qbJoinTarget").value;
    const whereField = document.getElementById("qbWhereField").value.trim();
    const whereOp = document.getElementById("qbWhereOp").value;
    const whereVal = document.getElementById("qbWhereVal").value.trim();
    const select = document.getElementById("qbSelect").value.trim();
    const limit = document.getElementById("qbLimit").value.trim();

    const lines = [`MATCH (a:${type})`];
    if (joinPred) {
      lines.push(joinTarget ? `JOIN (a)-[${joinPred}]->(b:${joinTarget})` : `JOIN (a)-[${joinPred}]->(b)`);
    }
    if (whereField && whereVal) {
      const quoted = (/^-?\d+(\.\d+)?$/.test(whereVal) || whereVal === "true" || whereVal === "false" || whereVal.startsWith('"'))
        ? whereVal
        : `"${whereVal.replace(/"/g, '\\"')}"`;
      lines.push(`WHERE ${whereField} ${whereOp} ${quoted}`);
    }
    if (select) lines.push(`SELECT ${select}`);
    if (limit) lines.push(`LIMIT ${limit}`);
    document.getElementById("dslTextarea").value = lines.join("\n");
  };

  const runDslQuery = async () => {
    const dsl = document.getElementById("dslTextarea").value.trim();
    if (!dsl) return;
    try {
      const res = await apiPost("/api/query", { dsl });
      document.getElementById("queryResultStats").textContent =
        `${res.rowCount} Treffer auf ${res.branch} (${res.commitId})`;
      const tbl = document.getElementById("queryResultTable");
      tbl.innerHTML = "";
      if (res.columns.length === 0) {
        tbl.appendChild(el("tr", {}, el("td", { className: "muted", textContent: "Keine Ergebnisse." })));
        return;
      }
      const thead = el("thead", {}, el("tr", {}, res.columns.map((c) => el("th", { textContent: c.key }))));
      const tbody = el("tbody");
      res.rows.forEach((r) => {
        const tr = el("tr");
        res.columns.forEach((c) => {
          if (c.kind === "ENTITY_BINDING") {
            const b = r.bindings[c.key];
            if (b) {
              tr.appendChild(el("td", {}, el("button", {
                className: "rel-link",
                textContent: `#${b.id} ${b.label}`,
                onClick: async () => {
                  state.selectedEntityId = b.id;
                  switchView("datasheet");
                  await loadEntityDetail(b.id, false);
                }
              })));
            } else {
              tr.appendChild(el("td", { textContent: "" }));
            }
          } else {
            tr.appendChild(el("td", { textContent: r.values[c.key] ?? "" }));
          }
        });
        tbody.appendChild(tr);
      });
      tbl.appendChild(thead);
      tbl.appendChild(tbody);
    } catch (err) {
      toast(err.message, "error");
    }
  };

  const loadHistory = async () => {
    state.history = await apiGet("/api/history");
    renderHistoryView();
  };

  const renderHistoryView = () => {
    if (!state.history) return;
    const commits = state.history.commits;
    const tbody = document.getElementById("historyBody");
    tbody.innerHTML = "";
    [...commits].reverse().forEach((c) => {
      tbody.appendChild(el("tr", {}, [
        el("td", { className: "cell-id", textContent: c.id }),
        el("td", { textContent: c.branchName }),
        el("td", { textContent: c.dataCategory }),
        el("td", { textContent: c.actor }),
        el("td", { textContent: c.reason }),
        el("td", { className: "mono", textContent: c.timestamp.slice(0, 19) }),
        el("td", { textContent: String(c.changeCount) }),
        el("td", {}, el("button", {
          className: "btn btn-sm btn-secondary",
          textContent: "Zeitreise (asOf)",
          onClick: () => setTimeTravel(c.id)
        }))
      ]));
    });

    const fromSel = document.getElementById("diffFromSelect");
    const toSel = document.getElementById("diffToSelect");
    fromSel.innerHTML = "";
    toSel.innerHTML = "";
    commits.forEach((c, idx) => {
      fromSel.appendChild(el("option", { value: c.id, textContent: `${c.id} (${c.reason.slice(0, 32)})` }));
      toSel.appendChild(el("option", { value: c.id, textContent: `${c.id} (${c.reason.slice(0, 32)})` }));
      if (idx === Math.max(0, commits.length - 2)) fromSel.value = c.id;
      if (idx === commits.length - 1) toSel.value = c.id;
    });
  };

  const runDiff = async () => {
    const from = document.getElementById("diffFromSelect").value;
    const to = document.getElementById("diffToSelect").value;
    if (!from || !to) return;
    try {
      const d = await apiGet("/api/diff", { from, to });
      const out = document.getElementById("diffOutput");
      out.innerHTML = "";
      if (d.empty) {
        out.textContent = `Keine strukturellen Unterschiede zwischen ${d.fromCommitId} und ${d.toCommitId}.`;
        return;
      }
      const addSection = (title, items, formatter) => {
        if (items.length === 0) return;
        const g = el("div", { className: "diff-group" }, [
          el("strong", { textContent: `${title} (${items.length})` }),
          el("ul", { style: "margin:4px 0 8px 18px;" }, items.slice(0, 40).map((it) => el("li", { textContent: formatter(it) })))
        ]);
        out.appendChild(g);
      };
      addSection("+ Hinzugefügte Entities", d.addedEntities, (e) => `#${e.id} [${e.typeName}] ${e.label}`);
      addSection("- Entfernte Entities", d.removedEntities, (e) => `#${e.id} [${e.typeName}] ${e.label}`);
      addSection("~ Aktualisierte Werte", d.updatedValues, (u) => `#${u.entityId}: "${u.previousValue}" → "${u.newValue}"`);
      addSection("+ Verbundene Kanten", d.addedEdges, (e) => `Edge #${e.id}: #${e.sourceId} -[${e.predicate}]-> #${e.targetId}`);
      addSection("- Getrennte Kanten", d.removedEdges, (e) => `Edge #${e.id}: #${e.sourceId} -[${e.predicate}]-> #${e.targetId}`);
    } catch (err) {
      toast(err.message, "error");
    }
  };

  const exportVegPatch = async () => {
    const from = document.getElementById("diffFromSelect").value;
    const to = document.getElementById("diffToSelect").value;
    if (!from || !to) return;
    try {
      const res = await apiGet("/api/patch", { from, to });
      document.getElementById("patchTextarea").value = res.patchText;
      toast(`.vegpatch (${from} → ${to}) exportiert`);
    } catch (err) {
      toast(err.message, "error");
    }
  };

  const submitVegPatch = async (mode) => {
    const patchText = document.getElementById("patchTextarea").value.trim();
    if (!patchText) {
      toast("Bitte zuerst einen .vegpatch-Text einfügen oder exportieren.", "error");
      return;
    }
    const out = document.getElementById("patchResultOutput");
    try {
      const res = await apiPost("/api/patch", { patchText, mode });
      out.innerHTML = "";
      if (res.alreadyApplied) {
        out.textContent = `Idempotent: Patch ${res.patchId} war bereits auf ${res.targetBranch} enthalten (${res.commitId}).`;
        toast("Patch bereits enthalten");
      } else if (res.merged) {
        out.textContent = `Erfolgreich (${res.mode}) in Branch '${res.targetBranch}' übernommen → Commit ${res.commitId} (Patch ${res.patchId}).`;
        toast(`Patch gemerged: ${res.commitId}`);
        await loadAll();
      } else {
        out.appendChild(el("strong", { style: "color:var(--danger);", textContent: `Merge-Konflikte (${res.conflicts.length}) — Zielbranch unverändert:` }));
        out.appendChild(el("ul", { style: "margin:4px 0 0 18px;" },
          res.conflicts.map((c) => el("li", { textContent: `[${c.type}] Entity #${c.entityId}: ${c.description}` }))));
        toast("Merge-Konflikt erkannt!", "error");
      }
    } catch (err) {
      toast(err.message, "error");
    }
  };

  const setTimeTravel = (commitId) => {
    state.asOf = commitId;
    loadAll();
  };

  const openNewRecordDialog = () => {
    if (!state.datasheet || state.datasheet.typeKind === "VALUE_TYPE") {
      toast("Bitte zuerst einen Objekttyp im Objektkatalog auswählen.", "error");
      return;
    }
    const dlg = document.getElementById("newRecordDialog");
    document.getElementById("newRecordDialogTitle").textContent = `Neuer Datensatz: ${state.datasheet.typeName}`;
    const fieldsDiv = document.getElementById("newRecordFields");
    fieldsDiv.innerHTML = "";
    const attrCols = state.datasheet.columns.filter((c) => c.kind === "ATTRIBUTE");
    if (attrCols.length === 0) {
      fieldsDiv.appendChild(el("div", { className: "muted", textContent: "Dieser Objekttyp besitzt aktuell nur Objekt-IDs." }));
    }
    attrCols.forEach((col) => {
      fieldsDiv.appendChild(el("label", {}, [
        el("span", { textContent: `${col.name} (${col.scalarType || "STRING"})` }),
        el("input", { name: col.name, placeholder: col.scalarType === "INT" ? "Ganzzahl..." : "Wert eingeben..." })
      ]));
    });
    dlg.showModal();
  };

  document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll(".tab-btn").forEach((btn) => {
      btn.addEventListener("click", () => switchView(btn.dataset.view));
    });
    document.getElementById("refreshBtn").addEventListener("click", loadAll);
    document.getElementById("branchSelect").addEventListener("change", (e) => {
      state.branch = e.target.value;
      state.asOf = "";
      state.diagramDirty = false;
      state.activeDiagramId = null;
      state.diagramLinkingNoteId = null;
      loadAll();
    });
    document.getElementById("asOfSelect").addEventListener("change", (e) => {
      state.asOf = e.target.value;
      state.diagramDirty = false;
      state.activeDiagramId = null;
      state.diagramLinkingNoteId = null;
      loadAll();
    });
    document.getElementById("exitTimeTravelBtn").addEventListener("click", () => {
      state.asOf = "";
      state.diagramDirty = false;
      state.activeDiagramId = null;
      state.diagramLinkingNoteId = null;
      loadAll();
    });
    document.getElementById("catalogFilterInput").addEventListener("input", (e) => {
      state.catalogFilter = e.target.value;
      renderCatalog();
    });
    document.getElementById("globalSearchInput").addEventListener("input", debounce((e) => {
      state.globalFilter = e.target.value;
      state.offset = 0;
      loadDatasheet();
    }, 220));
    document.getElementById("clearFiltersBtn").addEventListener("click", () => {
      state.colFilters = {};
      state.globalFilter = "";
      document.getElementById("globalSearchInput").value = "";
      state.offset = 0;
      loadDatasheet();
    });

    document.getElementById("newRecordBtn").addEventListener("click", openNewRecordDialog);
    document.getElementById("cancelNewRecordBtn").addEventListener("click", () => {
      document.getElementById("newRecordDialog").close();
    });
    document.getElementById("newRecordForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const formData = new FormData(e.target);
      const attributes = {};
      for (const [k, v] of formData.entries()) {
        if (String(v).trim() !== "") attributes[k] = String(v);
      }
      try {
        const res = await apiPost("/api/objects", { typeName: state.activeType, attributes });
        document.getElementById("newRecordDialog").close();
        state.selectedEntityId = res.entityId;
        toast(`Objekt #${res.entityId} angelegt (${res.commitId})`);
        await loadAll();
      } catch (err) { toast(err.message, "error"); }
    });

    document.getElementById("createObjectTypeForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const fd = new FormData(e.target);
      try {
        const res = await apiPost("/api/schema/object-type", { name: fd.get("name") });
        e.target.reset();
        toast(`Objekttyp ${res.name} angelegt (${res.commitId})`);
        await loadAll();
      } catch (err) { toast(err.message, "error"); }
    });
    document.getElementById("createValueTypeForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const fd = new FormData(e.target);
      try {
        const res = await apiPost("/api/schema/value-type", {
          name: fd.get("name"),
          scalarType: fd.get("scalarType"),
          uniqueValues: fd.get("uniqueValues") === "on"
        });
        e.target.reset();
        toast(`Werttyp ${res.name} angelegt (${res.commitId})`);
        await loadAll();
      } catch (err) { toast(err.message, "error"); }
    });
    document.getElementById("createPredicateForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const fd = new FormData(e.target);
      const maxOut = String(fd.get("maxOutgoing") || "").trim();
      const maxIn = String(fd.get("maxIncoming") || "").trim();
      try {
        const res = await apiPost("/api/schema/predicate", {
          name: fd.get("name"),
          domain: fd.get("domain"),
          range: fd.get("range"),
          maxOutgoing: maxOut ? Number(maxOut) : null,
          maxIncoming: maxIn ? Number(maxIn) : null,
          uniqueTriple: fd.get("uniqueTriple") === "on"
        });
        e.target.reset();
        toast(`Prädikat ${res.name} angelegt (${res.commitId})`);
        await loadAll();
      } catch (err) { toast(err.message, "error"); }
    });

    document.getElementById("applyBuilderBtn").addEventListener("click", buildDslFromUi);
    document.getElementById("runQueryBtn").addEventListener("click", runDslQuery);
    document.getElementById("dslTextarea").addEventListener("keydown", (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === "Enter") { e.preventDefault(); runDslQuery(); }
    });
    document.getElementById("runDiffBtn").addEventListener("click", runDiff);
    document.getElementById("exportPatchBtn").addEventListener("click", exportVegPatch);
    document.getElementById("mergePatchBtn").addEventListener("click", () => submitVegPatch("MERGE"));
    document.getElementById("applyPatchBtn").addEventListener("click", () => submitVegPatch("APPLY"));

    bindDiagramToolbarEvents();

    loadAll();
  });

  // =====================================================================
  // Graphical Diagram Editor (Views, Explicit Tables, Edge Handles, Notes)
  // =====================================================================

  const SVG_NS = "http://www.w3.org/2000/svg";

  const svgEl = (tag, attrs = {}) => {
    const node = document.createElementNS(SVG_NS, tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (v !== undefined && v !== null) node.setAttribute(k, String(v));
    }
    return node;
  };

  const syncDiagramViewsFromServer = (diagData) => {
    if (state.diagramDirty) {
      return;
    }
    const serverViews = (diagData && Array.isArray(diagData.views) ? diagData.views : []).map((v) => ({
      viewId: v.viewId,
      title: v.title || v.viewId,
      description: v.description || "",
      viewMode: v.viewMode || "DIAGRAM",
      defaultDsl: v.defaultDsl || "",
      tables: (v.tables || []).map((t) => ({
        typeName: t.typeName,
        x: Number(t.x ?? 40),
        y: Number(t.y ?? 40),
        width: Number(t.width ?? 235)
      })),
      edges: (v.edges || []).map((e) => ({
        edgeKey: e.edgeKey || `${e.sourceType}|${e.predicate}|${e.targetType}`,
        sourceType: e.sourceType,
        predicate: e.predicate,
        targetType: e.targetType,
        ctrlX: e.ctrlX !== null && e.ctrlX !== undefined ? Number(e.ctrlX) : null,
        ctrlY: e.ctrlY !== null && e.ctrlY !== undefined ? Number(e.ctrlY) : null
      })),
      notes: (v.notes || []).map((n) => ({
        noteId: n.noteId,
        title: n.title || "Infobox",
        text: n.text || "",
        color: n.color || "info",
        x: Number(n.x ?? 60),
        y: Number(n.y ?? 60),
        targets: (n.targets || []).map((tgt) => ({ kind: tgt.kind, ref: tgt.ref }))
      })),
      persisted: true
    }));

    state.diagramViews = serverViews;
    if (state.diagramViews.length === 0) {
      state.diagramViews.push({
        viewId: "view-1",
        title: "Standard-Ansicht",
        description: "",
        viewMode: "DIAGRAM",
        defaultDsl: "",
        tables: [],
        edges: [],
        notes: [],
        persisted: false
      });
    }
    if (!state.activeDiagramId || !state.diagramViews.some((v) => v.viewId === state.activeDiagramId)) {
      state.activeDiagramId = state.diagramViews[0].viewId;
    }
    state.diagramDirty = false;
  };

  const activeDiagramView = () => {
    let v = state.diagramViews.find((item) => item.viewId === state.activeDiagramId);
    if (!v && state.diagramViews.length > 0) {
      v = state.diagramViews[0];
      state.activeDiagramId = v.viewId;
    }
    if (!v) {
      v = {
        viewId: "view-1",
        title: "Standard-Ansicht",
        description: "",
        viewMode: "DIAGRAM",
        defaultDsl: "",
        tables: [],
        edges: [],
        notes: [],
        persisted: false
      };
      state.diagramViews.push(v);
      state.activeDiagramId = v.viewId;
    }
    return v;
  };

  const setDiagramDirty = (dirty) => {
    state.diagramDirty = dirty;
    const badge = document.getElementById("diagramDirtyBadge");
    if (badge) badge.classList.toggle("hidden", !dirty);
  };

  const computeVisibleEdgesForView = (view) => {
    if (!state.overview) return [];
    const onCanvas = new Set(view.tables.map((t) => t.typeName));
    const savedByKey = new Map(view.edges.map((e) => [e.edgeKey, e]));

    const visible = [];
    for (const p of state.overview.predicates) {
      if (p.rangeKind !== "OBJECT_TYPE") continue;
      if (!onCanvas.has(p.domainName) || !onCanvas.has(p.rangeName)) continue;
      const edgeKey = `${p.domainName}|${p.name}|${p.rangeName}`;
      const layout = savedByKey.get(edgeKey);
      visible.push({
        edgeKey,
        predicate: p.name,
        sourceType: p.domainName,
        targetType: p.rangeName,
        count: p.count,
        maxOutgoing: p.maxOutgoing,
        maxIncoming: p.maxIncoming,
        ctrlX: layout && layout.ctrlX !== null && layout.ctrlX !== undefined ? layout.ctrlX : null,
        ctrlY: layout && layout.ctrlY !== null && layout.ctrlY !== undefined ? layout.ctrlY : null,
        fanIndex: 0,
        fanTotal: 1
      });
    }

    const pairGroups = new Map();
    visible.forEach((edge) => {
      const pairKey = [edge.sourceType, edge.targetType].sort().join("::");
      if (!pairGroups.has(pairKey)) pairGroups.set(pairKey, []);
      pairGroups.get(pairKey).push(edge);
    });
    pairGroups.forEach((group) => {
      group.forEach((edge, idx) => {
        edge.fanIndex = idx;
        edge.fanTotal = group.length;
      });
    });
    return visible;
  };

  const upsertSavedEdgeLayout = (view, edge, ctrlX, ctrlY) => {
    let existing = view.edges.find((item) => item.edgeKey === edge.edgeKey);
    if (!existing) {
      existing = {
        edgeKey: edge.edgeKey,
        sourceType: edge.sourceType,
        predicate: edge.predicate,
        targetType: edge.targetType,
        ctrlX: null,
        ctrlY: null
      };
      view.edges.push(existing);
    }
    existing.ctrlX = ctrlX;
    existing.ctrlY = ctrlY;
    return existing;
  };

  const clipRectBoundary = (cx, cy, hw, hh, tx, ty) => {
    const dx = tx - cx;
    const dy = ty - cy;
    if (Math.abs(dx) < 0.01 && Math.abs(dy) < 0.01) {
      return { x: cx + hw, y: cy };
    }
    const sx = dx !== 0 ? hw / Math.abs(dx) : Infinity;
    const sy = dy !== 0 ? hh / Math.abs(dy) : Infinity;
    const scale = Math.min(sx, sy);
    return { x: cx + dx * scale, y: cy + dy * scale };
  };

  const computeEdgeGeometry = (view, edge, tableBoxes) => {
    const src = tableBoxes.get(edge.sourceType);
    const tgt = tableBoxes.get(edge.targetType);
    if (!src || !tgt) return null;

    const fanShift = (edge.fanIndex - (edge.fanTotal - 1) / 2) * 36;

    if (edge.sourceType === edge.targetType) {
      const mx = edge.ctrlX !== null ? edge.ctrlX : src.x + src.w + 88 + Math.abs(fanShift);
      const my = edge.ctrlY !== null ? edge.ctrlY : src.cy - 20 + fanShift * 0.5;
      const p0 = { x: src.x + src.w, y: src.y + 24 };
      const p2 = { x: src.x + src.w, y: Math.min(src.y + src.h - 14, src.cy + 34) };
      const qx = 2 * mx - 0.5 * (p0.x + p2.x);
      const qy = 2 * my - 0.5 * (p0.y + p2.y);
      return { p0, p2, qx, qy, mx, my };
    }

    let mx = edge.ctrlX;
    let my = edge.ctrlY;
    if (mx === null || my === null) {
      const dx = tgt.cx - src.cx;
      const dy = tgt.cy - src.cy;
      const len = Math.hypot(dx, dy) || 1;
      const nx = -dy / len;
      const ny = dx / len;
      const curveAmount = fanShift !== 0 ? fanShift : 18;
      mx = Math.round((src.cx + tgt.cx) / 2 + nx * curveAmount);
      my = Math.round((src.cy + tgt.cy) / 2 + ny * curveAmount);
    }

    const p0 = clipRectBoundary(src.cx, src.cy, src.w / 2, src.h / 2, mx, my);
    const p2 = clipRectBoundary(tgt.cx, tgt.cy, tgt.w / 2, tgt.h / 2, mx, my);
    const qx = 2 * mx - 0.5 * (p0.x + p2.x);
    const qy = 2 * my - 0.5 * (p0.y + p2.y);
    return { p0, p2, qx, qy, mx, my };
  };

  const redrawDiagramSvgOnly = () => {
    const svg = document.getElementById("diagramSvgLayer");
    if (!svg || !state.overview) return;
    const view = activeDiagramView();
    const visibleEdges = computeVisibleEdgesForView(view);

    const tableBoxes = new Map();
    view.tables.forEach((t) => {
      const node = document.querySelector(`.diag-table-node[data-type="${CSS.escape(t.typeName)}"]`);
      const w = node ? node.offsetWidth : (t.width || 235);
      const h = node ? node.offsetHeight : 140;
      tableBoxes.set(t.typeName, { x: t.x, y: t.y, w, h, cx: t.x + w / 2, cy: t.y + h / 2 });
    });

    const noteBoxes = new Map();
    view.notes.forEach((n) => {
      const node = document.querySelector(`.diag-note-node[data-note-id="${CSS.escape(n.noteId)}"]`);
      const w = node ? node.offsetWidth : 220;
      const h = node ? node.offsetHeight : 110;
      noteBoxes.set(n.noteId, { x: n.x, y: n.y, w, h, cx: n.x + w / 2, cy: n.y + h / 2 });
    });

    svg.innerHTML = "";
    const defs = svgEl("defs");
    const addMarker = (id, color) => {
      const m = svgEl("marker", {
        id,
        viewBox: "0 0 10 10",
        refX: "8.5",
        refY: "5",
        markerWidth: "7",
        markerHeight: "7",
        orient: "auto-start-reverse"
      });
      m.appendChild(svgEl("path", { d: "M 0 1.2 L 9 5 L 0 8.8 z", fill: color }));
      defs.appendChild(m);
    };
    addMarker("arrow-rel", "#0f5478");
    addMarker("arrow-rel-selected", "#d97706");
    addMarker("arrow-note-info", "#2563eb");
    addMarker("arrow-note-warn", "#d97706");
    addMarker("arrow-note-success", "#059669");
    addMarker("arrow-note-danger", "#dc2626");
    svg.appendChild(defs);

    const edgeMidpoints = new Map();
    visibleEdges.forEach((edge) => {
      const geom = computeEdgeGeometry(view, edge, tableBoxes);
      if (!geom) return;
      edgeMidpoints.set(edge.edgeKey, { x: geom.mx, y: geom.my });
      const selected = state.diagramSelection?.kind === "EDGE" && state.diagramSelection.ref === edge.edgeKey;
      svg.appendChild(svgEl("path", {
        d: `M ${geom.p0.x.toFixed(1)} ${geom.p0.y.toFixed(1)} Q ${geom.qx.toFixed(1)} ${geom.qy.toFixed(1)} ${geom.p2.x.toFixed(1)} ${geom.p2.y.toFixed(1)}`,
        fill: "none",
        stroke: selected ? "#d97706" : "#0f5478",
        "stroke-width": selected ? "2.6" : "2",
        "marker-end": `url(#${selected ? "arrow-rel-selected" : "arrow-rel"})`
      }));

      const handleNode = document.querySelector(`.diag-edge-handle[data-edge-key="${CSS.escape(edge.edgeKey)}"]`);
      if (handleNode) {
        handleNode.style.left = `${geom.mx}px`;
        handleNode.style.top = `${geom.my}px`;
      }
    });

    const noteColorHex = {
      info: "#2563eb",
      warn: "#d97706",
      success: "#059669",
      danger: "#dc2626"
    };
    view.notes.forEach((n) => {
      const nb = noteBoxes.get(n.noteId);
      if (!nb) return;
      const colKey = noteColorHex[n.color] ? n.color : "info";
      (n.targets || []).forEach((tgt) => {
        let pEnd = null;
        if (tgt.kind === "TABLE") {
          const tb = tableBoxes.get(tgt.ref);
          if (tb) {
            pEnd = clipRectBoundary(tb.cx, tb.cy, tb.w / 2, tb.h / 2, nb.cx, nb.cy);
          }
        } else if (tgt.kind === "EDGE") {
          const ep = edgeMidpoints.get(tgt.ref);
          if (ep) {
            pEnd = ep;
          }
        }
        if (!pEnd) return;
        const pStart = clipRectBoundary(nb.cx, nb.cy, nb.w / 2, nb.h / 2, pEnd.x, pEnd.y);
        const midX = (pStart.x + pEnd.x) / 2;
        const midY = (pStart.y + pEnd.y) / 2 - 18;
        svg.appendChild(svgEl("path", {
          d: `M ${pStart.x.toFixed(1)} ${pStart.y.toFixed(1)} Q ${midX.toFixed(1)} ${midY.toFixed(1)} ${pEnd.x.toFixed(1)} ${pEnd.y.toFixed(1)}`,
          fill: "none",
          stroke: noteColorHex[colKey],
          "stroke-width": "1.8",
          "stroke-dasharray": "5 4",
          "marker-end": `url(#arrow-note-${colKey})`
        }));
      });
    });
  };

  const attachDragBehavior = (dragHandleEl, targetEl, getStartPos, onMove, onDragEnd, onClickOnly) => {
    dragHandleEl.addEventListener("mousedown", (ev) => {
      if (ev.button !== 0 || ev.target.closest("button")) return;
      ev.preventDefault();
      const startMouseX = ev.clientX;
      const startMouseY = ev.clientY;
      const orig = getStartPos();
      let moved = false;
      let rafId = null;

      const handleMove = (mv) => {
        const dx = mv.clientX - startMouseX;
        const dy = mv.clientY - startMouseY;
        if (!moved && Math.hypot(dx, dy) < 4) return;
        moved = true;
        const nextX = Math.max(16, Math.min(1760, Math.round(orig.x + dx)));
        const nextY = Math.max(16, Math.min(1060, Math.round(orig.y + dy)));
        onMove(nextX, nextY, targetEl);
        if (!rafId) {
          rafId = requestAnimationFrame(() => {
            rafId = null;
            redrawDiagramSvgOnly();
          });
        }
      };

      const handleUp = () => {
        document.removeEventListener("mousemove", handleMove);
        document.removeEventListener("mouseup", handleUp);
        if (rafId) {
          cancelAnimationFrame(rafId);
          rafId = null;
        }
        if (moved) {
          setDiagramDirty(true);
          redrawDiagramSvgOnly();
          if (onDragEnd) onDragEnd();
        } else if (onClickOnly) {
          onClickOnly();
        }
      };

      document.addEventListener("mousemove", handleMove);
      document.addEventListener("mouseup", handleUp);
    });
  };

  const renderDiagramView = () => {
    if (!state.overview) return;
    const view = activeDiagramView();
    const historical = !!state.overview.historical;

    // Toolbar dropdowns
    const viewSel = document.getElementById("diagramViewSelect");
    viewSel.innerHTML = "";
    state.diagramViews.forEach((v) => {
      const opt = el("option", {
        value: v.viewId,
        textContent: v.persisted ? v.title : `${v.title} (Entwurf)`
      });
      if (v.viewId === view.viewId) opt.selected = true;
      viewSel.appendChild(opt);
    });

    const addSel = document.getElementById("diagramAddTableSelect");
    addSel.innerHTML = "";
    const alreadyOnCanvas = new Set(view.tables.map((t) => t.typeName));
    const availableTypes = state.overview.objectTypes.filter(
      (t) => !DIAGRAM_SYSTEM_TYPES.has(t.name) && !alreadyOnCanvas.has(t.name)
    );
    if (availableTypes.length === 0) {
      addSel.appendChild(el("option", { value: "", textContent: "-- Alle Tabellen auf Zeichenfläche --" }));
    } else {
      availableTypes.forEach((t) => {
        addSel.appendChild(el("option", { value: t.name, textContent: `${t.name} (${t.count})` }));
      });
    }

    document.getElementById("addTableToDiagramBtn").disabled = historical || availableTypes.length === 0;
    document.getElementById("addDiagramNoteBtn").disabled = historical;
    document.getElementById("saveDiagramBtn").disabled = historical;
    document.getElementById("deleteDiagramViewBtn").disabled = historical;
    document.getElementById("diagramDirtyBadge").classList.toggle("hidden", !state.diagramDirty);

    const canvasWrap = document.getElementById("diagramCanvasWrap");
    canvasWrap.classList.toggle("linking-active", !!state.diagramLinkingNoteId);
    const linkingBanner = document.getElementById("diagramLinkingBanner");
    linkingBanner.classList.toggle("hidden", !state.diagramLinkingNoteId);
    if (state.diagramLinkingNoteId) {
      const linkingNote = view.notes.find((n) => n.noteId === state.diagramLinkingNoteId);
      document.getElementById("diagramLinkingText").textContent =
        `Pfeil-Modus für „${linkingNote ? linkingNote.title : state.diagramLinkingNoteId}“: Klicken Sie auf eine Tabelle oder einen Verbindungspunkt, um einen Pfeil zu setzen/lösen.`;
    }

    const emptyHint = document.getElementById("diagramEmptyHint");
    emptyHint.classList.toggle("hidden", view.tables.length > 0 || view.notes.length > 0);

    // Render Nodes
    const nodesLayer = document.getElementById("diagramNodesLayer");
    nodesLayer.innerHTML = "";

    const typeCounts = new Map(state.overview.objectTypes.map((t) => [t.name, t.count]));
    const attrPreds = state.overview.predicates.filter((p) => p.rangeKind === "VALUE_TYPE");
    const relPreds = state.overview.predicates.filter((p) => p.rangeKind === "OBJECT_TYPE");

    view.tables.forEach((t) => {
      const isSelected = state.diagramSelection?.kind === "TABLE" && state.diagramSelection.ref === t.typeName;
      const attrs = attrPreds.filter((p) => p.domainName === t.typeName);
      const anyAttrs = attrPreds.filter((p) => p.domainName === "ANY_OBJECT");
      const displayAttrs = (attrs.length > 0 ? attrs : anyAttrs).slice(0, 8);
      const rels = relPreds.filter((p) => p.domainName === t.typeName);

      const headerTitle = el("span", { className: "diag-table-title", textContent: t.typeName });
      const countBadge = el("span", { className: "badge", textContent: String(typeCounts.get(t.typeName) ?? 0) });
      const openBtn = el("button", {
        className: "diag-icon-btn",
        type: "button",
        title: "Datenblatt öffnen",
        textContent: "↗",
        onClick: (ev) => {
          ev.stopPropagation();
          selectType(t.typeName);
        }
      });
      const removeBtn = el("button", {
        className: "diag-icon-btn",
        type: "button",
        title: "Tabelle aus dieser Ansicht entfernen",
        textContent: "×",
        disabled: historical ? "true" : null,
        onClick: (ev) => {
          ev.stopPropagation();
          removeTableFromView(view, t.typeName);
        }
      });

      const headerEl = el("div", { className: "diag-table-header" }, [headerTitle, countBadge, openBtn, removeBtn]);
      const bodyChildren = displayAttrs.map((a) => el("div", { className: "diag-field-row" }, [
        el("span", { className: "diag-field-name", textContent: a.name }),
        el("span", { className: "diag-field-type", textContent: a.rangeName })
      ]));
      if (displayAttrs.length === 0) {
        bodyChildren.push(el("div", { className: "muted", textContent: "(Nur Objekt-ID / generische Attribute)" }));
      }
      if (rels.length > 0) {
        bodyChildren.push(el("div", { className: "diag-rel-divider" }, rels.slice(0, 5).map((r) =>
          el("div", { className: "diag-rel-row", textContent: `→ ${r.name} → ${r.rangeName}` })
        )));
      }

      const card = el("div", {
        className: `diag-table-node${isSelected ? " selected" : ""}`,
        "data-type": t.typeName,
        style: `left:${t.x}px;top:${t.y}px;width:${t.width || 235}px;`
      }, [
        headerEl,
        el("div", { className: "diag-table-body" }, bodyChildren)
      ]);

      card.addEventListener("click", (ev) => {
        if (ev.target.closest("button")) return;
        if (state.diagramLinkingNoteId) {
          toggleNoteTarget(view, state.diagramLinkingNoteId, "TABLE", t.typeName);
          return;
        }
        state.diagramSelection = { kind: "TABLE", ref: t.typeName };
        updateDiagramSelectionClasses();
        renderDiagramInspector();
      });

      if (!historical) {
        attachDragBehavior(
          headerEl,
          card,
          () => ({ x: t.x, y: t.y }),
          (nx, ny, domNode) => {
            t.x = nx;
            t.y = ny;
            domNode.style.left = `${nx}px`;
            domNode.style.top = `${ny}px`;
          },
          () => renderDiagramInspector(),
          () => {
            if (state.diagramLinkingNoteId) {
              toggleNoteTarget(view, state.diagramLinkingNoteId, "TABLE", t.typeName);
            } else {
              state.diagramSelection = { kind: "TABLE", ref: t.typeName };
              updateDiagramSelectionClasses();
              renderDiagramInspector();
            }
          }
        );
      }

      nodesLayer.appendChild(card);
    });

    // Edge Midpoint Handles
    const visibleEdges = computeVisibleEdgesForView(view);
    visibleEdges.forEach((edge) => {
      const isSelected = state.diagramSelection?.kind === "EDGE" && state.diagramSelection.ref === edge.edgeKey;
      const cardText = edge.maxOutgoing === 1 ? "n:1" : (edge.maxIncoming === 1 ? "1:n" : "n:m");
      const handle = el("div", {
        className: `diag-edge-handle${isSelected ? " selected" : ""}`,
        "data-edge-key": edge.edgeKey,
        title: `${edge.sourceType} -[${edge.predicate}]-> ${edge.targetType} (ziehen zum Verschieben)`,
        textContent: `${edge.predicate} (${edge.count}) · ${cardText}`
      });

      if (!historical) {
        attachDragBehavior(
          handle,
          handle,
          () => {
            const left = parseFloat(handle.style.left) || 200;
            const top = parseFloat(handle.style.top) || 200;
            return { x: left, y: top };
          },
          (nx, ny, domNode) => {
            upsertSavedEdgeLayout(view, edge, nx, ny);
            edge.ctrlX = nx;
            edge.ctrlY = ny;
            domNode.style.left = `${nx}px`;
            domNode.style.top = `${ny}px`;
          },
          () => renderDiagramInspector(),
          () => {
            if (state.diagramLinkingNoteId) {
              toggleNoteTarget(view, state.diagramLinkingNoteId, "EDGE", edge.edgeKey);
            } else {
              state.diagramSelection = { kind: "EDGE", ref: edge.edgeKey };
              updateDiagramSelectionClasses();
              renderDiagramInspector();
            }
          }
        );
      } else {
        handle.addEventListener("click", () => {
          state.diagramSelection = { kind: "EDGE", ref: edge.edgeKey };
          updateDiagramSelectionClasses();
          renderDiagramInspector();
        });
      }

      nodesLayer.appendChild(handle);
    });

    // Infobox Notes
    view.notes.forEach((n) => {
      const isSelected = state.diagramSelection?.kind === "NOTE" && state.diagramSelection.ref === n.noteId;
      const isLinking = state.diagramLinkingNoteId === n.noteId;

      const linkBtn = el("button", {
        className: "diag-icon-btn",
        type: "button",
        title: "Pfeil zu Tabelle oder Verbindung setzen",
        textContent: isLinking ? "Pfeil ✓" : "Pfeil +",
        disabled: historical ? "true" : null,
        onClick: (ev) => {
          ev.stopPropagation();
          state.diagramLinkingNoteId = isLinking ? null : n.noteId;
          state.diagramSelection = { kind: "NOTE", ref: n.noteId };
          renderDiagramView();
        }
      });
      const delBtn = el("button", {
        className: "diag-icon-btn",
        type: "button",
        title: "Infobox entfernen",
        textContent: "×",
        disabled: historical ? "true" : null,
        onClick: (ev) => {
          ev.stopPropagation();
          view.notes = view.notes.filter((item) => item.noteId !== n.noteId);
          if (state.diagramLinkingNoteId === n.noteId) state.diagramLinkingNoteId = null;
          if (state.diagramSelection?.kind === "NOTE" && state.diagramSelection.ref === n.noteId) {
            state.diagramSelection = null;
          }
          setDiagramDirty(true);
          renderDiagramView();
        }
      });

      const headerEl = el("div", { className: "diag-note-header" }, [
        el("span", { className: "diag-table-title", textContent: n.title || "Infobox" }),
        linkBtn,
        delBtn
      ]);

      const targetChips = (n.targets || []).map((tgt) => el("span", {
        className: "diag-target-chip",
        textContent: tgt.kind === "TABLE" ? `→ ${tgt.ref}` : `→ ${tgt.ref.split("|")[1] || tgt.ref}`
      }));

      const noteCard = el("div", {
        className: `diag-note-node note-${n.color || "info"}${isSelected ? " selected" : ""}`,
        "data-note-id": n.noteId,
        style: `left:${n.x}px;top:${n.y}px;`
      }, [
        headerEl,
        el("div", { className: "diag-note-body" }, [
          el("div", { textContent: n.text || "(Doppelklick / Inspector rechts zum Bearbeiten)" }),
          targetChips.length > 0 ? el("div", { className: "diag-note-targets" }, targetChips) : null
        ])
      ]);

      noteCard.addEventListener("click", (ev) => {
        if (ev.target.closest("button")) return;
        state.diagramSelection = { kind: "NOTE", ref: n.noteId };
        updateDiagramSelectionClasses();
        renderDiagramInspector();
      });

      if (!historical) {
        attachDragBehavior(
          headerEl,
          noteCard,
          () => ({ x: n.x, y: n.y }),
          (nx, ny, domNode) => {
            n.x = nx;
            n.y = ny;
            domNode.style.left = `${nx}px`;
            domNode.style.top = `${ny}px`;
          },
          () => renderDiagramInspector(),
          () => {
            state.diagramSelection = { kind: "NOTE", ref: n.noteId };
            updateDiagramSelectionClasses();
            renderDiagramInspector();
          }
        );
      }

      nodesLayer.appendChild(noteCard);
    });

    redrawDiagramSvgOnly();
    requestAnimationFrame(redrawDiagramSvgOnly);
    renderDiagramInspector();
  };

  const updateDiagramSelectionClasses = () => {
    document.querySelectorAll(".diag-table-node").forEach((n) => {
      n.classList.toggle("selected",
        state.diagramSelection?.kind === "TABLE" && state.diagramSelection.ref === n.dataset.type);
    });
    document.querySelectorAll(".diag-edge-handle").forEach((h) => {
      h.classList.toggle("selected",
        state.diagramSelection?.kind === "EDGE" && state.diagramSelection.ref === h.dataset.edgeKey);
    });
    document.querySelectorAll(".diag-note-node").forEach((n) => {
      n.classList.toggle("selected",
        state.diagramSelection?.kind === "NOTE" && state.diagramSelection.ref === n.dataset.noteId);
    });
  };

  const removeTableFromView = (view, typeName) => {
    view.tables = view.tables.filter((t) => t.typeName !== typeName);
    view.edges = view.edges.filter((e) => e.sourceType !== typeName && e.targetType !== typeName);
    const remainingEdgeKeys = new Set(computeVisibleEdgesForView(view).map((e) => e.edgeKey));
    view.notes.forEach((n) => {
      n.targets = (n.targets || []).filter((tgt) =>
        (tgt.kind === "TABLE" && tgt.ref !== typeName) || (tgt.kind === "EDGE" && remainingEdgeKeys.has(tgt.ref))
      );
    });
    if (state.diagramSelection && state.diagramSelection.ref === typeName) {
      state.diagramSelection = null;
    }
    setDiagramDirty(true);
    renderDiagramView();
  };

  const toggleNoteTarget = (view, noteId, kind, ref) => {
    const note = view.notes.find((n) => n.noteId === noteId);
    if (!note) return;
    note.targets = note.targets || [];
    const idx = note.targets.findIndex((t) => t.kind === kind && t.ref === ref);
    if (idx >= 0) {
      note.targets.splice(idx, 1);
      toast(`Pfeil gelöst (${ref})`);
    } else {
      note.targets.push({ kind, ref });
      toast(`Pfeil gesetzt → ${ref}`);
    }
    setDiagramDirty(true);
    renderDiagramView();
  };

  const deriveDslForDiagram = (view, focus) => {
    if (focus?.kind === "EDGE") {
      const [src, pred, tgt] = focus.ref.split("|");
      return `MATCH (a:${src})\nJOIN (a)-[${pred}]->(b:${tgt})\nLIMIT 50`;
    }
    if (focus?.kind === "TABLE") {
      return `MATCH (a:${focus.ref})\nLIMIT 50`;
    }
    if (view.defaultDsl && view.defaultDsl.trim()) {
      return view.defaultDsl.trim();
    }
    if (view.tables.length === 0) {
      return "";
    }
    const visibleEdges = computeVisibleEdgesForView(view);
    if (visibleEdges.length === 0) {
      return `MATCH (a:${view.tables[0].typeName})\nLIMIT 50`;
    }
    // Build a connected MATCH + JOIN chain over tables on the canvas
    const startEdge = visibleEdges[0];
    const lines = [`MATCH (n0:${startEdge.sourceType})`];
    const aliasByType = new Map([[startEdge.sourceType, "n0"]]);
    const queue = [startEdge.sourceType];
    let aliasCounter = 1;
    const usedEdges = new Set();

    while (queue.length > 0 && aliasCounter < 6) {
      const cur = queue.shift();
      const curAlias = aliasByType.get(cur);
      for (const e of visibleEdges) {
        if (e.sourceType !== cur || usedEdges.has(e.edgeKey)) continue;
        usedEdges.add(e.edgeKey);
        if (!aliasByType.has(e.targetType)) {
          const nextAlias = `n${aliasCounter++}`;
          aliasByType.set(e.targetType, nextAlias);
          lines.push(`JOIN (${curAlias})-[${e.predicate}]->(${nextAlias}:${e.targetType})`);
          queue.push(e.targetType);
        }
      }
    }
    if (lines.length === 1) {
      lines.push(`JOIN (n0)-[${startEdge.predicate}]->(n1:${startEdge.targetType})`);
    }
    lines.push("LIMIT 50");
    return lines.join("\n");
  };

  const openDerivedQuery = (dsl) => {
    if (!dsl) {
      toast("Bitte zuerst mindestens eine Tabelle in die Ansicht aufnehmen.", "error");
      return;
    }
    document.getElementById("dslTextarea").value = dsl;
    switchView("query");
    runDslQuery();
  };

  const renderDiagramInspector = () => {
    const box = document.getElementById("diagramInspector");
    if (!box || !state.overview) return;
    const view = activeDiagramView();
    const historical = !!state.overview.historical;
    const visibleEdges = computeVisibleEdgesForView(view);
    box.innerHTML = "";

    // Always show View Settings card at top
    const titleInp = el("input", { value: view.title, disabled: historical ? "true" : null });
    titleInp.addEventListener("input", () => {
      view.title = titleInp.value;
      setDiagramDirty(true);
      const opt = document.querySelector(`#diagramViewSelect option[value="${CSS.escape(view.viewId)}"]`);
      if (opt) opt.textContent = view.persisted ? view.title : `${view.title} (Entwurf)`;
    });

    const modeSel = el("select", { disabled: historical ? "true" : null }, [
      el("option", { value: "DIAGRAM", textContent: "Reines Schema-/Beziehungsdiagramm" }),
      el("option", { value: "QUERY_TEMPLATE", textContent: "Vorlage für Abfrage-Ansicht (später)" }),
      el("option", { value: "EDIT_TEMPLATE", textContent: "Vorlage für Bearbeitungs-Ansicht (später)" })
    ]);
    modeSel.value = view.viewMode || "DIAGRAM";
    modeSel.addEventListener("change", () => {
      view.viewMode = modeSel.value;
      setDiagramDirty(true);
    });

    const descArea = el("textarea", {
      rows: 2,
      placeholder: "Zweck dieser Ansicht...",
      disabled: historical ? "true" : null
    });
    descArea.value = view.description || "";
    descArea.addEventListener("input", () => {
      view.description = descArea.value;
      setDiagramDirty(true);
    });

    const dslInp = el("textarea", {
      rows: 3,
      className: "mono",
      placeholder: "Optionales DSL-Template für Abfrage-/Bearbeitungs-Ansicht...",
      disabled: historical ? "true" : null
    });
    dslInp.value = view.defaultDsl || "";
    dslInp.addEventListener("input", () => {
      view.defaultDsl = dslInp.value;
      setDiagramDirty(true);
    });

    box.appendChild(el("div", {}, [
      el("h4", { textContent: "Aktive Diagramm-Ansicht" }),
      el("div", { className: "muted", style: "font-size:11px;margin-bottom:6px;", textContent: `ID: ${view.viewId} · ${view.tables.length} Tabellen · ${visibleEdges.length} Verbindungen · ${view.notes.length} Infoboxen` })
    ]));

    const viewGroup = el("div", { className: "inspector-group" }, [
      el("label", {}, [el("span", { textContent: "Titel der Ansicht" }), titleInp]),
      el("label", {}, [el("span", { textContent: "Verwendungszweck / Option" }), modeSel]),
      el("label", {}, [el("span", { textContent: "Beschreibung" }), descArea]),
      el("label", {}, [el("span", { textContent: "Abfrage-/View-Template (GraphQueryDsl)" }), dslInp]),
      el("div", { style: "display:flex;gap:6px;flex-wrap:wrap;" }, [
        el("button", {
          className: "btn btn-sm btn-secondary",
          type: "button",
          textContent: "DSL aus Diagramm füllen",
          disabled: historical ? "true" : null,
          onClick: () => {
            const generated = deriveDslForDiagram({ ...view, defaultDsl: "" }, null);
            view.defaultDsl = generated;
            dslInp.value = generated;
            setDiagramDirty(true);
          }
        }),
        el("button", {
          className: "btn btn-sm btn-primary",
          type: "button",
          textContent: "Im Abfrage-Assistent testen",
          onClick: () => openDerivedQuery(dslInp.value.trim() || deriveDslForDiagram(view, null))
        })
      ])
    ]);
    box.appendChild(viewGroup);

    // Selected Item Section
    const sel = state.diagramSelection;
    if (!sel) {
      box.appendChild(el("div", {
        className: "inspector-group muted",
        textContent: "Tipp: Klicken Sie auf eine Tabelle, eine Verknüpfung oder eine Infobox, um Details, Pfeile oder Abfrage-Sprünge zu verwalten."
      }));
      return;
    }

    if (sel.kind === "TABLE") {
      const tbl = view.tables.find((t) => t.typeName === sel.ref);
      if (!tbl) return;
      box.appendChild(el("div", { className: "inspector-group" }, [
        el("h4", { textContent: `Tabelle: ${tbl.typeName}` }),
        el("div", { className: "muted", textContent: `Position: x=${tbl.x}, y=${tbl.y} · Breite=${tbl.width || 235}px` }),
        el("div", { style: "display:flex;gap:6px;flex-wrap:wrap;" }, [
          el("button", {
            className: "btn btn-sm btn-primary",
            type: "button",
            textContent: "Im Datenblatt / Editor öffnen",
            onClick: () => selectType(tbl.typeName)
          }),
          el("button", {
            className: "btn btn-sm btn-secondary",
            type: "button",
            textContent: "Abfrage für Tabelle erzeugen",
            onClick: () => openDerivedQuery(deriveDslForDiagram(view, { kind: "TABLE", ref: tbl.typeName }))
          }),
          el("button", {
            className: "btn btn-sm btn-danger",
            type: "button",
            textContent: "Aus Ansicht entfernen",
            disabled: historical ? "true" : null,
            onClick: () => removeTableFromView(view, tbl.typeName)
          })
        ])
      ]));
    } else if (sel.kind === "EDGE") {
      const edge = visibleEdges.find((e) => e.edgeKey === sel.ref);
      if (!edge) return;
      box.appendChild(el("div", { className: "inspector-group" }, [
        el("h4", { textContent: `Verknüpfung: ${edge.predicate}` }),
        el("div", { className: "mono", style: "font-size:11.5px;", textContent: `${edge.sourceType} → ${edge.targetType} (${edge.count} Kanten)` }),
        el("div", { className: "muted", textContent: edge.ctrlX !== null ? `Handle-Position: x=${edge.ctrlX}, y=${edge.ctrlY}` : "Handle-Position: Automatisch (Mittelpunkt)" }),
        el("div", { style: "display:flex;gap:6px;flex-wrap:wrap;" }, [
          el("button", {
            className: "btn btn-sm btn-primary",
            type: "button",
            textContent: "JOIN-Abfrage ausführen",
            onClick: () => openDerivedQuery(deriveDslForDiagram(view, { kind: "EDGE", ref: edge.edgeKey }))
          }),
          el("button", {
            className: "btn btn-sm btn-secondary",
            type: "button",
            textContent: "Kurvenpunkt zurücksetzen",
            disabled: historical ? "true" : null,
            onClick: () => {
              upsertSavedEdgeLayout(view, edge, null, null);
              setDiagramDirty(true);
              renderDiagramView();
            }
          })
        ])
      ]));
    } else if (sel.kind === "NOTE") {
      const note = view.notes.find((n) => n.noteId === sel.ref);
      if (!note) return;

      const noteTitleInp = el("input", { value: note.title, disabled: historical ? "true" : null });
      noteTitleInp.addEventListener("input", () => {
        note.title = noteTitleInp.value;
        setDiagramDirty(true);
        const titleDom = document.querySelector(`.diag-note-node[data-note-id="${CSS.escape(note.noteId)}"] .diag-table-title`);
        if (titleDom) titleDom.textContent = note.title || "Infobox";
      });

      const noteTextInp = el("textarea", { rows: 3, disabled: historical ? "true" : null });
      noteTextInp.value = note.text || "";
      noteTextInp.addEventListener("input", () => {
        note.text = noteTextInp.value;
        setDiagramDirty(true);
        const bodyDom = document.querySelector(`.diag-note-node[data-note-id="${CSS.escape(note.noteId)}"] .diag-note-body > div:first-child`);
        if (bodyDom) bodyDom.textContent = note.text;
      });

      const colorSel = el("select", { disabled: historical ? "true" : null }, [
        el("option", { value: "info", textContent: "Blau (Info)" }),
        el("option", { value: "warn", textContent: "Gelb (Hinweis)" }),
        el("option", { value: "success", textContent: "Grün (Verifiziert)" }),
        el("option", { value: "danger", textContent: "Rot (Warnung / Konflikt)" })
      ]);
      colorSel.value = note.color || "info";
      colorSel.addEventListener("change", () => {
        note.color = colorSel.value;
        setDiagramDirty(true);
        renderDiagramView();
      });

      const targetOptions = el("select", { disabled: historical ? "true" : null });
      view.tables.forEach((t) => targetOptions.appendChild(el("option", { value: `TABLE::${t.typeName}`, textContent: `Tabelle: ${t.typeName}` })));
      visibleEdges.forEach((e) => targetOptions.appendChild(el("option", { value: `EDGE::${e.edgeKey}`, textContent: `Verbindung: ${e.sourceType} -[${e.predicate}]-> ${e.targetType}` })));

      const targetListDiv = el("div", { style: "display:flex;flex-direction:column;gap:4px;" });
      (note.targets || []).forEach((tgt) => {
        targetListDiv.appendChild(el("div", { className: "rel-card" }, [
          el("span", { style: "font-size:11px;", textContent: `${tgt.kind === "TABLE" ? "Tabelle" : "Verbindung"}: ${tgt.ref.replace(/\|/g, " → ")}` }),
          !historical ? el("button", {
            className: "btn btn-sm btn-danger",
            type: "button",
            textContent: "Lösen",
            onClick: () => toggleNoteTarget(view, note.noteId, tgt.kind, tgt.ref)
          }) : null
        ]));
      });

      box.appendChild(el("div", { className: "inspector-group" }, [
        el("h4", { textContent: `Infobox (${note.noteId})` }),
        el("label", {}, [el("span", { textContent: "Überschrift" }), noteTitleInp]),
        el("label", {}, [el("span", { textContent: "Text" }), noteTextInp]),
        el("label", {}, [el("span", { textContent: "Farbe" }), colorSel]),
        el("div", { className: "muted", style: "font-size:11px;", textContent: `Position: x=${note.x}, y=${note.y}` }),
        el("div", { style: "display:flex;gap:6px;flex-wrap:wrap;" }, [
          el("button", {
            className: "btn btn-sm btn-primary",
            type: "button",
            textContent: state.diagramLinkingNoteId === note.noteId ? "Pfeil-Modus beenden" : "Pfeil-Modus (Canvas klicken)",
            disabled: historical ? "true" : null,
            onClick: () => {
              state.diagramLinkingNoteId = state.diagramLinkingNoteId === note.noteId ? null : note.noteId;
              renderDiagramView();
            }
          }),
          el("button", {
            className: "btn btn-sm btn-secondary",
            type: "button",
            textContent: "+ Pfeil setzen",
            disabled: (historical || targetOptions.options.length === 0) ? "true" : null,
            onClick: () => {
              if (!targetOptions.value) return;
              const splitIdx = targetOptions.value.indexOf("::");
              const k = targetOptions.value.slice(0, splitIdx);
              const ref = targetOptions.value.slice(splitIdx + 2);
              note.targets = note.targets || [];
              if (!note.targets.some((t) => t.kind === k && t.ref === ref)) {
                note.targets.push({ kind: k, ref });
                setDiagramDirty(true);
                renderDiagramView();
              }
            }
          })
        ]),
        targetOptions,
        targetListDiv
      ]));
    }
  };

  const seedExampleDiagram = (view) => {
    if (!state.overview) return;
    const allNames = state.overview.objectTypes
      .filter((t) => !DIAGRAM_SYSTEM_TYPES.has(t.name))
      .map((t) => t.name);
    const hasKbSample = ["ArchitectureDecision", "Invariant", "Stage", "Module"].every((n) => allNames.includes(n));
    const chosen = hasKbSample
      ? ["ArchitectureDecision", "Invariant", "Stage", "Module"]
      : allNames.slice(0, 4);
    if (chosen.length === 0) {
      toast("Keine Objekttypen im Schema vorhanden.", "error");
      return;
    }

    const coords = [
      { x: 40, y: 40 },
      { x: 370, y: 40 },
      { x: 700, y: 40 },
      { x: 370, y: 310 }
    ];
    view.tables = chosen.map((name, idx) => ({
      typeName: name,
      x: coords[idx % coords.length].x,
      y: coords[idx % coords.length].y,
      width: 235
    }));
    view.edges = [];
    const visEdges = computeVisibleEdgesForView(view);
    const noteTargets = [{ kind: "TABLE", ref: chosen[Math.min(1, chosen.length - 1)] }];
    if (visEdges.length > 0) {
      noteTargets.push({ kind: "EDGE", ref: visEdges[0].edgeKey });
    }
    view.notes = [{
      noteId: `note-${Date.now().toString(36)}`,
      title: "Architektur-Hinweis",
      text: "Explizit gewählte Tabellen + Verknüpfung. Weitere Ansichten lassen sich oben über '+ Neue Ansicht' anlegen.",
      color: "warn",
      x: 50,
      y: 330,
      targets: noteTargets
    }];
    setDiagramDirty(true);
    renderDiagramView();
  };

  const bindDiagramToolbarEvents = () => {
    document.getElementById("diagramViewSelect").addEventListener("change", (e) => {
      state.activeDiagramId = e.target.value;
      state.diagramSelection = null;
      state.diagramLinkingNoteId = null;
      renderDiagramView();
    });

    document.getElementById("newDiagramViewBtn").addEventListener("click", () => {
      const idx = state.diagramViews.length + 1;
      let candidateId = `view-${idx}`;
      while (state.diagramViews.some((v) => v.viewId === candidateId)) {
        candidateId = `view-${Date.now().toString(36)}`;
      }
      const created = {
        viewId: candidateId,
        title: `Ansicht ${idx}`,
        description: "",
        viewMode: "DIAGRAM",
        defaultDsl: "",
        tables: [],
        edges: [],
        notes: [],
        persisted: false
      };
      state.diagramViews.push(created);
      state.activeDiagramId = candidateId;
      state.diagramSelection = null;
      state.diagramLinkingNoteId = null;
      setDiagramDirty(true);
      renderDiagramView();
    });

    document.getElementById("seedDiagramViewBtn").addEventListener("click", () => {
      seedExampleDiagram(activeDiagramView());
    });

    document.getElementById("addTableToDiagramBtn").addEventListener("click", () => {
      const sel = document.getElementById("diagramAddTableSelect");
      const typeName = sel.value;
      if (!typeName) return;
      const view = activeDiagramView();
      if (view.tables.some((t) => t.typeName === typeName)) return;
      const idx = view.tables.length;
      const col = idx % 3;
      const row = Math.floor(idx / 3);
      view.tables.push({
        typeName,
        x: 40 + col * 310,
        y: 40 + row * 250,
        width: 235
      });
      state.diagramSelection = { kind: "TABLE", ref: typeName };
      setDiagramDirty(true);
      renderDiagramView();
    });

    document.getElementById("addDiagramNoteBtn").addEventListener("click", () => {
      const view = activeDiagramView();
      const idx = view.notes.length + 1;
      const note = {
        noteId: `note-${Date.now().toString(36)}-${idx}`,
        title: `Infobox ${idx}`,
        text: "Erklärung oder Hinweis zur Tabelle / Verbindung...",
        color: "info",
        x: 70 + ((idx - 1) % 3) * 250,
        y: 340 + Math.floor((idx - 1) / 3) * 140,
        targets: []
      };
      view.notes.push(note);
      state.diagramSelection = { kind: "NOTE", ref: note.noteId };
      setDiagramDirty(true);
      renderDiagramView();
    });

    document.getElementById("cancelLinkingModeBtn").addEventListener("click", () => {
      state.diagramLinkingNoteId = null;
      renderDiagramView();
    });

    document.getElementById("diagramToQueryBtn").addEventListener("click", () => {
      const view = activeDiagramView();
      openDerivedQuery(deriveDslForDiagram(view, state.diagramSelection));
    });

    document.getElementById("saveDiagramBtn").addEventListener("click", async () => {
      const view = activeDiagramView();
      try {
        const visibleEdgeKeys = new Set(computeVisibleEdgesForView(view).map((e) => e.edgeKey));
        const res = await apiPost("/api/diagrams", {
          viewId: view.viewId,
          title: view.title,
          description: view.description,
          viewMode: view.viewMode,
          defaultDsl: view.defaultDsl,
          tables: view.tables,
          edges: view.edges.filter((e) => visibleEdgeKeys.has(e.edgeKey)),
          notes: view.notes,
          reason: `Studio: Save diagram view '${view.title}'`
        });
        view.persisted = true;
        setDiagramDirty(false);
        toast(`Ansicht '${view.title}' in DB gespeichert (${res.commitId})`);
        await loadAll();
      } catch (err) {
        toast(err.message, "error");
      }
    });

    document.getElementById("diagramNodesLayer").addEventListener("click", (ev) => {
      if (ev.target === ev.currentTarget) {
        state.diagramSelection = null;
        updateDiagramSelectionClasses();
        renderDiagramInspector();
      }
    });

    document.getElementById("deleteDiagramViewBtn").addEventListener("click", async () => {
      const view = activeDiagramView();
      try {
        if (view.persisted) {
          const res = await apiPost("/api/diagrams/delete", {
            viewId: view.viewId,
            reason: `Studio: Delete diagram view '${view.title}'`
          });
          toast(`Ansicht '${view.title}' gelöscht (${res.commitId})`);
        } else {
          toast(`Entwurf '${view.title}' verworfen`);
        }
        state.diagramViews = state.diagramViews.filter((v) => v.viewId !== view.viewId);
        state.activeDiagramId = state.diagramViews.length > 0 ? state.diagramViews[0].viewId : null;
        state.diagramSelection = null;
        state.diagramLinkingNoteId = null;
        setDiagramDirty(false);
        await loadAll();
      } catch (err) {
        toast(err.message, "error");
      }
    });
  };
})();
